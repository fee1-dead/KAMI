package me.zeroeightsix.kami.setting

import com.mojang.authlib.GameProfile
import glm_.asHexString
import glm_.vec2.Vec2
import imgui.ColorEditFlag
import imgui.ImGui
import io.github.fablabsmc.fablabs.api.fiber.v1.annotation.AnnotatedSettings
import io.github.fablabsmc.fablabs.api.fiber.v1.annotation.processor.ParameterizedTypeProcessor
import io.github.fablabsmc.fablabs.api.fiber.v1.builder.ConfigTreeBuilder
import io.github.fablabsmc.fablabs.api.fiber.v1.exception.ValueDeserializationException
import io.github.fablabsmc.fablabs.api.fiber.v1.schema.type.BooleanSerializableType.BOOLEAN
import io.github.fablabsmc.fablabs.api.fiber.v1.schema.type.RecordSerializableType
import io.github.fablabsmc.fablabs.api.fiber.v1.schema.type.StringSerializableType.DEFAULT_STRING
import io.github.fablabsmc.fablabs.api.fiber.v1.schema.type.derived.*
import io.github.fablabsmc.fablabs.api.fiber.v1.serialization.FiberSerialization
import io.github.fablabsmc.fablabs.api.fiber.v1.serialization.JanksonValueSerializer
import io.github.fablabsmc.fablabs.api.fiber.v1.tree.*
import me.zeroeightsix.kami.Colour
import me.zeroeightsix.kami.KamiMod
import me.zeroeightsix.kami.event.ConfigSaveEvent
import me.zeroeightsix.kami.feature.FeatureManager
import me.zeroeightsix.kami.feature.FeatureManager.fullFeatures
import me.zeroeightsix.kami.feature.FindSettings
import me.zeroeightsix.kami.feature.FullFeature
import me.zeroeightsix.kami.feature.module.Module
import me.zeroeightsix.kami.gui.widgets.EnabledWidgets
import me.zeroeightsix.kami.gui.widgets.PinnableWidget
import me.zeroeightsix.kami.gui.widgets.TextPinnableWidget
import me.zeroeightsix.kami.gui.windows.modules.Modules
import me.zeroeightsix.kami.mixin.extend.getMap
import me.zeroeightsix.kami.splitFirst
import me.zeroeightsix.kami.then
import me.zeroeightsix.kami.util.Bind
import me.zeroeightsix.kami.util.Friends
import me.zeroeightsix.kami.util.Target
import me.zeroeightsix.kami.util.Targets
import net.minecraft.client.util.InputUtil
import net.minecraft.server.command.CommandSource
import org.reflections.Reflections
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.Stream

object KamiConfig {

    const val CONFIG_FILENAME = "KAMI_config.json5"

    /** Config types **/

    // This should be done with an enumconfigtype but unfortunately map types only accept string types as keys,
    // maybe should make an issue for this on the fiber repo
    val targetType = ConfigTypes.STRING.derive(Target::class.java, {
        Target.valueOf(it)
    }, {
        it.name
    })

    val targetsTypeProcessor = ParameterizedTypeProcessor<Targets<*>> {
        fun <M, S> createTargetsType(metaType: ConfigType<M, S, *>): MapConfigType<Targets<M>, S> =
            ConfigTypes.makeMap(targetType, metaType).derive(Targets::class.java, {
                Targets(it)
            }, {
                it
            })

        createTargetsType(it[0])
    }

    val colourType =
        ConfigTypes.makeList(ConfigTypes.FLOAT)
            .derive(Colour::class.java, { list ->
                Colour(list[0], list[1], list[2], list[3])
            }, {
                listOf(it.r, it.g, it.b, it.a)
            })
            .extend({
                it.asRGBA().asHexString
            }, {
                Colour.fromRGBA(it.toInt(radix = 16))
            }, { name, colour ->
                with(ImGui) {
                    val floats = colour.asFloats().toFloatArray()
                    colorEdit4(
                        name,
                        floats,
                        ColorEditFlag.AlphaBar.i
                    ).then {
                        Colour(floats[0], floats[1], floats[2], floats[3])
                    }
                }
            })

    val colourModeType = ConfigTypes.makeEnum(TextPinnableWidget.CompiledText.Part.ColourMode::class.java)
    val partSerializableType = RecordSerializableType(
        mapOf(
            Pair("obfuscated", BOOLEAN),
            Pair("bold", BOOLEAN),
            Pair("strike", BOOLEAN),
            Pair("underline", BOOLEAN),
            Pair("italic", BOOLEAN),
            Pair("shadow", BOOLEAN),
            Pair("extraspace", BOOLEAN),
            Pair("colourMode", colourModeType.serializedType),
            Pair("type", DEFAULT_STRING),
            Pair("value", DEFAULT_STRING),
            Pair("colour", colourType.serializedType)
        )
    )
    val partType = RecordConfigType(partSerializableType, TextPinnableWidget.CompiledText.Part::class.java, {
        val obfuscated = it["obfuscated"] as Boolean
        val bold = it["bold"] as Boolean
        val strike = it["strike"] as Boolean
        val underline = it["underline"] as Boolean
        val italic = it["italic"] as Boolean
        val shadow = it["shadow"] as Boolean
        val extraspace = it["extraspace"] as Boolean
        val colourMode = colourModeType.toRuntimeType(it["colourMode"] as String)
        val type = it["type"] as String
        val value = it["value"] as String
        val colour = colourType.toRuntimeType(it["colour"] as List<BigDecimal>)

        val part = when (type) {
            "literal" -> TextPinnableWidget.CompiledText.LiteralPart(
                value,
                obfuscated,
                bold,
                strike,
                underline,
                italic,
                shadow,
                colourMode,
                extraspace
            )
            "variable" -> TextPinnableWidget.CompiledText.VariablePart(TextPinnableWidget.varMap[value]?.let { it() }
                ?: TextPinnableWidget.varMap["none"]!!(),
                obfuscated,
                bold,
                strike,
                underline,
                italic,
                shadow,
                colourMode,
                extraspace
            )
            else -> TextPinnableWidget.CompiledText.LiteralPart(
                "Invalid part",
                obfuscated,
                bold,
                strike,
                underline,
                italic,
                shadow,
                colourMode,
                extraspace
            )
        }
        part.colour = colour.asVec4()
        part
    }, {
        val (type, value) = when (it) {
            is TextPinnableWidget.CompiledText.LiteralPart -> "literal" to it.string
            is TextPinnableWidget.CompiledText.VariablePart -> "variable" to it.variable.name
            else -> throw IllegalStateException("Unknown part type")
        }
        mapOf(
            "obfuscated" to it.obfuscated,
            "bold" to it.bold,
            "strike" to it.strike,
            "underline" to it.underline,
            "italic" to it.italic,
            "shadow" to it.shadow,
            "extraspace" to it.extraspace,
            "colourMode" to colourModeType.toSerializedType(it.colourMode),
            "type" to type,
            "value" to value,
            "colour" to colourType.toSerializedType(Colour.fromVec4(it.colour))
        )
    })
    val compiledTextType = ConfigTypes.makeList(partType).derive(
        TextPinnableWidget.CompiledText::class.java,
        {
            TextPinnableWidget.CompiledText(it.toMutableList())
        },
        {
            it.parts
        }
    )
    val listOfCompiledTextType = ConfigTypes.makeList(compiledTextType)
    val positionType = ConfigTypes.makeEnum(PinnableWidget.Position::class.java)
    val textPinnableSerializableType = RecordSerializableType(
        mapOf(
            "texts" to listOfCompiledTextType.serializedType,
            "title" to DEFAULT_STRING,
            "position" to positionType.serializedType
        )
    )
    val textPinnableWidgetType = RecordConfigType(textPinnableSerializableType, TextPinnableWidget::class.java, {
        val title = it["title"] as String
        val position = positionType.toRuntimeType(it["position"] as String?)
        val texts = listOfCompiledTextType.toRuntimeType(it["texts"] as List<List<Map<String, Any>>>?)
        TextPinnableWidget(title, texts.toMutableList(), position)
    }, {
        mapOf(
            "texts" to listOfCompiledTextType.toSerializedType(it.text),
            "title" to it.title,
            "position" to positionType.toSerializedType(it.position)
        )
    })
    val widgetsType = ConfigTypes.makeList(textPinnableWidgetType).derive(EnabledWidgets.Widgets::class.java, {
        EnabledWidgets.Widgets(it.toMutableList())
    }, {
        it
    })

    val moduleType = ConfigTypes.STRING.derive<Module>(Module::class.java, { name ->
        FeatureManager.modules.find { it.name == name }
    }, { m ->
        m.name
    })
    val moduleListType = ConfigTypes.makeList(moduleType)
    val modulesGroupsType = ConfigTypes.makeMap(ConfigTypes.STRING, moduleListType)
    val windowsType = ConfigTypes.makeMap(ConfigTypes.STRING, modulesGroupsType)
        .derive(Modules.Windows::class.java, {
            val windows = mutableListOf<Modules.ModuleWindow>()
            for ((nameAndId, groups) in it) {
                val (id, name) = nameAndId.splitFirst('-')
                windows.add(
                    Modules.ModuleWindow(
                        name,
                        groups = groups.mapValues { it.value.toMutableList() }.toMutableMap(),
                        id = id.toInt()
                    )
                )
            }
            Modules.Windows(windows)
        }, {
            val map = mutableMapOf<String, Map<String, List<Module>>>()
            for (window in it) {
                val nameAndId = "${window.id}-${window.title}"
                map[nameAndId] = window.groups.toMutableMap()
            }
            map
        })

    val bindType = ConfigTypes.STRING
        .derive(Bind::class.java, {
            var s = it.toLowerCase()

            fun remove(part: String): Boolean {
                val removed = s.replace(part, "")
                val changed = s != removed
                s = removed
                return changed
            }

            val ctrl = remove("ctrl+")
            val alt = remove("alt+")
            val shift = remove("shift+")

            s = s.removePrefix("key.keyboard.")

            Bind(
                ctrl,
                alt,
                shift,
                bindMap[s] ?: Bind.Code.none()
            )
        }, {
            var s = ""
            if (it.isCtrl) s += "ctrl+"
            if (it.isAlt) s += "alt+"
            if (it.isShift) s += "shift+"
            s += it.code.translationKey
            s
        })
        .extend({ _, bind ->
            with(ImGui) {
                text("Bound to $bind") // TODO: Highlight bind in another color?
                sameLine(0, -1)
                if (button("Bind", Vec2())) { // TODO: Bind popup?
                    // Maybe just display "Press a key" instead of the normal "Bound to ...", and wait for a key press.
                }
            }
            null
        }, { _, b ->
            val range = 0..b.remaining.lastIndexOf('+')
            val left = b.remaining.substring(range)
            val right = b.remaining.toLowerCase().removeRange(range)
            Stream.concat(
                listOf("ctrl+", "shift+", "alt+").stream(),
                bindMap.keys.stream()
            ).forEach {
                if (it.startsWith(right)) b.suggest("$left$it")
            }
            b.buildFuture()
        })

    val profileType =
        ConfigTypes.makeMap(ConfigTypes.STRING, ConfigTypes.STRING)
            .derive(
                GameProfile::class.java,
                { map: Map<String?, String?> ->
                    GameProfile(
                        UUID.fromString(map["uuid"]),
                        map["name"]
                    )
                }
            ) { profile: GameProfile ->
                mapOf(
                    "uuid" to profile.id.toString(),
                    "name" to profile.name
                )
            }
    val friendsType =
        ConfigTypes.makeList(profileType)

    private val bindMap = (InputUtil.Type.KEYSYM.getMap()).mapNotNull {
        val name = it.value.translationKey.also { s ->
            if (!s.startsWith("key.keyboard")) return@mapNotNull null
        }.removePrefix("key.keyboard.").replace('.', '-')

        Pair(name, Bind.Code(it.value!!))
    }.toMap()

    /** Extending base types **/

    init {
        ConfigTypes.BOOLEAN.extend(
            {
                it.toString()
            }, {
                it.toBoolean()
            }, { name, bool ->
                val bArray = booleanArrayOf(bool)
                if (ImGui.checkbox(name, bArray)) {
                    bArray[0]
                } else null
            }, { _, b -> CommandSource.suggestMatching(listOf("true", "false"), b) }
        )
    }

    fun installBaseExtensions(node: ConfigNode) {
        when (node) {
            is ConfigBranch ->
                node.items.forEach { installBaseExtensions(it) }
            is ConfigLeaf<*> -> {
                installBaseExtension(node)
            }
        }
    }

    fun <T : ConfigLeaf<*>> installBaseExtension(node: T) {
        val type = node.getAnyRuntimeConfigType()

        when (type) {
            // NumberConfigTypes use BigDecimal: we can assume that we can just pass a BigDecimal to this leaf and it'll take it
            is NumberConfigType<*> -> {
                val type = (type as NumberConfigType<Any>)
                type.extend({
                    it.toString()
                }, {
                    type.toRuntimeType(BigDecimal(it))
                }, { name, value ->
                    val sType = type.serializedType
                    val float = type.toSerializedType(value).toFloat()
                    val array = floatArrayOf(float)
                    ImGui.dragFloat(
                        name, array, 0, vSpeed = sType.increment?.toFloat() ?: 1.0f,
                        vMin = sType.minimum?.toFloat() ?: 0.0f,
                        vMax = sType.maximum?.toFloat() ?: 0.0f,
                        format = "%s"
                    ).then {
                        // If the value changed, return it
                        // of course we also need to correct the type again
                        type.toRuntimeType(BigDecimal.valueOf(array[0].toDouble()))
                    } // If it didn't change, this will return null
                }, type = "number")
            }
            is EnumConfigType<*> -> {
                val type = (type as EnumConfigType<Any>)
                val values = type.serializedType.validValues.toList()
                type.extend({
                    it.toString()
                }, {
                    type.toRuntimeType(it)
                }, { name, value ->
                    val index = values.indexOf(type.toSerializedType(value))
                    val array = intArrayOf(index)
                    ImGui.combo(name, array, values).then {
                        type.toRuntimeType(values[array[0]])
                    }
                }, { _, b ->
                    CommandSource.suggestMatching(values, b)
                }, type = "enum")
            }
        }
    }

    /** Contructing & (De)serialisation **/

    val config = initAndLoad()

    fun initAndLoad(): ConfigTree? {
        return try {
            val config = constructConfiguration()
            try {
                loadConfiguration(config)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            KamiMod.log.info("Settings loaded")
            config
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Resolves all features annotated by [FindSettings] or affected by the annotation on a superclass.
     *
     * @return All found roots, mapped to a set of affected classes
     * @see FeatureManager.findAnnotatedFeatures
     */
    fun findAnnotatedSettings(): Map<String, Set<Class<*>>> {
        val reflections = Reflections("me.zeroeightsix.kami")
        return reflections.getTypesAnnotatedWith(FindSettings::class.java).filter {
            it.isAnnotationPresent(FindSettings::class.java)
        }.map {
            val fsAnnot = it.getAnnotation(FindSettings::class.java)!!
            fsAnnot to if (fsAnnot.findDescendants) {
                reflections.getSubTypesOf(it)
            } else {
                Collections.singleton(it)
            }
        }.groupBy {
            it.first.settingsRoot
        }.mapValues {
            it.value.stream().flatMap { it.second.stream() }.collect(Collectors.toSet())
        }
    }

    private fun constructConfiguration(): ConfigTree {
        val settings = AnnotatedSettings.builder()
            .collectMembersRecursively()
            .collectOnlyAnnotatedMembers()
            .useNamingConvention(ProperCaseConvention)
            .registerTypeMapping(Targets::class.java, targetsTypeProcessor)
            .registerTypeMapping(Bind::class.java, bindType)
            .registerTypeMapping(GameProfile::class.java, profileType)
            .registerTypeMapping(Colour::class.java, colourType)
            .registerTypeMapping(Modules.Windows::class.java, windowsType)
            .registerTypeMapping(EnabledWidgets.Widgets::class.java, widgetsType)
            .registerSettingProcessor(
                SettingVisibility.Constant::class.java,
                ConstantVisibilityAnnotationProcessor
            )
            .registerSettingProcessor(
                SettingVisibility.Method::class.java,
                MethodVisibilityAnnotationProcessor
            )
            .build()

        val builder = ConfigTree.builder()

        // TODO: Eventually, when all modules are kotlin objects, we can reasonably assume that they'll have an INSTANCE field.
        // Then we can just add @FindSettings to the module class and remove this method
        constructFeaturesConfiguration(builder, settings)

        findAnnotatedSettings().also { println(it) }.entries.forEach { (root, list) ->
            val builder = when {
                root.isEmpty() -> builder
                else -> builder.fork(root)
            }

            list.forEach {
                try {
                    val instance = it.getDeclaredField("INSTANCE").get(null)
                    builder.applyFromPojo(instance, settings)
                } catch (e: NoSuchFieldError) {
                    println("Couldn't get ${it.simpleName}'s instance, probably not a kotlin object!")
                    e.printStackTrace()
                }
            }

            if (root.isNotEmpty()) {
                val config = builder.build()
                list.forEach {
                    val instance = it.getDeclaredField("INSTANCE").get(null)
                    if (instance is HasConfig) {
                        instance.config = config
                    }
                }
            }

        }

        val built = builder.build()

        val mirror =
            PropertyMirror.create(friendsType)
        mirror.mirror(
            built.lookupLeaf(
                "Friends",
                friendsType.serializedType
            )
        )
        Friends.mirror = mirror

        installBaseExtensions(built)

        return built
    }

    private fun constructFeaturesConfiguration(
        builder: ConfigTreeBuilder,
        settings: AnnotatedSettings?
    ) {
        val features = builder.fork("features")
        // only full features because they have names
        fullFeatures.forEach(Consumer { f: FullFeature ->
            f.config = features.fork(f.name)
                .applyFromPojo(f, settings)
                .build()
        })
        features.finishBranch()
    }

    fun loadConfiguration() = loadConfiguration(this.config)

    @Throws(IOException::class, ValueDeserializationException::class)
    private fun loadConfiguration(config: ConfigTree?) {
        config?.let {
            try {
                FiberSerialization.deserialize(
                    config,
                    Files.newInputStream(
                        Paths.get(CONFIG_FILENAME),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ
                    ),
                    JanksonValueSerializer(false)
                )
            } catch (e: java.nio.file.NoSuchFileException) {
                saveConfiguration(config)
                KamiMod.log.info("KAMI configuration file generated!")
            } catch (e: Exception) {
                KamiMod.log.error("Failed to load KAMI configuration file", e)
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun saveConfiguration() = saveConfiguration(this.config)

    @Throws(IOException::class)
    private fun saveConfiguration(config: ConfigTree?) {
        val event = ConfigSaveEvent(config)
        KamiMod.EVENT_BUS.post(event)
        if (event.isCancelled) return
        config?.let {
            FiberSerialization.serialize(
                it,
                Files.newOutputStream(Paths.get(CONFIG_FILENAME)),
                JanksonValueSerializer(false)
            )
        }
    }

}
