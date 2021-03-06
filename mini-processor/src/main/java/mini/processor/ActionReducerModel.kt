package mini.processor

import com.squareup.kotlinpoet.*
import mini.Action
import org.jetbrains.annotations.TestOnly
import javax.lang.model.type.TypeMirror
import javax.tools.StandardLocation

const val DEBUG_MODE = false

class ActionReducerModel(private val reducerFunctions: List<ReducerFuncModel>,
                         private val extraActionTypes: List<TypeMirror>) {
    private val actionType = elementUtils.getTypeElement("mini.Action").asType()
    private val stores: List<StoreModel>
    private val typeToReducersList: List<Pair<TypeMirror, List<ReducerFuncModel>>>

    companion object {
        const val MINI_COMMON_PACKAGE_NAME = "mini"
        const val MINI_PROCESSOR_PACKAGE_NAME = "mini.processor"
        const val STORE_CLASS_NAME = "Store"
        const val ACTION_REDUCER_CLASS_NAME = "MiniActionReducer"
        const val ACTION_REDUCER_INTERFACE = "ActionReducer"
    }

    init {
        stores = reducerFunctions
            .distinctBy { it.storeElement.qualifiedName() }
            .map {
                StoreModel(
                    fieldName = it.storeFieldName,
                    element = it.storeElement)
            }

        val typesFromReducers = reducerFunctions
            .map { it.parameterType.element.asType() }
        //.filter { Modifier.FINAL in it.asTypeElement().modifiers }

        val actionTypes = (extraActionTypes + typesFromReducers)
            .distinctBy { it.asTypeName() }

        typeToReducersList =
            actionTypes.map { actionType ->
                val functions = reducerFunctions.filter {
                    actionType isSubtypeOf it.parameterType.element.asType()
                }
                actionType to functions.sortedBy { it.priority }
            }.sortedWith(
                Comparator { a, b ->
                    val aType = a.first
                    val bType = b.first
                    //More generic types go lower in the when branch
                    when {
                        aType isSubtypeOf bType -> -1
                        bType isSubtypeOf aType -> 1
                        else -> 0
                    }
                }
            )
    }

    fun generateDispatcherFile() {
        //Generate FileSpec
        val builder = FileSpec.builder(MINI_COMMON_PACKAGE_NAME, ACTION_REDUCER_CLASS_NAME)
        //Start generating file
        val kotlinFile = builder
            .addType(TypeSpec.classBuilder(ACTION_REDUCER_CLASS_NAME)
                .addSuperinterface(ClassName(MINI_COMMON_PACKAGE_NAME, ACTION_REDUCER_INTERFACE))
                .addMainConstructor()
                .addStoreProperties()
                .addDispatcherFunction()
                .build())
            .build()

        val kotlinFileObject = env.filer.createResource(StandardLocation.SOURCE_OUTPUT,
            MINI_PROCESSOR_PACKAGE_NAME, "${kotlinFile.name}.kt")
        val openWriter = kotlinFileObject.openWriter()
        kotlinFile.writeTo(openWriter)
        openWriter.close()
    }

    private fun TypeSpec.Builder.addMainConstructor(): TypeSpec.Builder {
        return primaryConstructor(FunSpec.constructorBuilder()
            .addParameter("stores", getStoreMapType())
            .build())
    }

    private fun TypeSpec.Builder.addStoreProperties(): TypeSpec.Builder {
        stores.forEach { storeModel ->
            val typeName = storeModel.element.asType().asTypeName()
            addProperty(PropertySpec.builder(storeModel.fieldName, typeName)
                .addModifiers(KModifier.PRIVATE)
                .initializer(CodeBlock.of("stores.get(%T::class.java) as %T", typeName, typeName))
                .build()
            )
        }
        return this
    }

    private fun TypeSpec.Builder.addDispatcherFunction(): TypeSpec.Builder {
        val reduceBuilder = with(FunSpec.builder("reduce")) {

            addParameter(ParameterSpec.builder("action", Action::class).build())
            addModifiers(KModifier.OVERRIDE)

            addStatement("when (action) {%>")
            val whenBranches = typeToReducersList.filter { (_, reducers) ->
                !reducers.isEmpty()
            }
            whenBranches.forEach { (parameterType, reducers) ->
                addStatement("is %T -> {%>", parameterType.asTypeName())
                reducers.forEach { reducer ->
                    val storeFieldName = reducer.storeFieldName

                    val callString = if (reducer.hasStateParameter) {
                        CodeBlock.of("action, $storeFieldName.state")
                    } else {
                        CodeBlock.of("action")
                    }

                    addCode(CodeBlock.builder()
                        .add("$storeFieldName.setStateInternal(")
                        .add("$storeFieldName.${reducer.funcName}($callString)")
                        .add(")\n")
                        .build())
                }
                addStatement("%<}")
            }
            addStatement("%<}")
            return@with this
        }

        return addFunction(reduceBuilder.build())
    }

    private fun getStoreMapType(): ParameterizedTypeName {
        val anyStoreType = ClassName(MINI_COMMON_PACKAGE_NAME, STORE_CLASS_NAME).wildcardType() //Store<*>
        val anyClassType = ClassName("java.lang", "Class").wildcardType() //Class<*>
        return mapTypeOf(anyClassType, anyStoreType)
    }

    private fun getActionTagMapType(): ParameterizedTypeName {
        val anyClassType = ClassName("kotlin.reflect", "KClass").wildcardType() //Class<*>
        return mapTypeOf(anyClassType, anyClassType.listTypeName())
    }

    @TestOnly
    fun generateStoreProperties(className: String) = TypeSpec.classBuilder(className).addStoreProperties()

    @TestOnly
    fun generateMainConstructor(className: String) = TypeSpec.classBuilder(className).addMainConstructor()

    @TestOnly
    fun generateReduceFunc(className: String) = TypeSpec.classBuilder(className).addDispatcherFunction()

    @TestOnly
    fun generateActionReducer(className: String, packageName: String) = TypeSpec.classBuilder(className)
        .addSuperinterface(ClassName(packageName, ACTION_REDUCER_INTERFACE))
        .addMainConstructor()
        .addStoreProperties()
        .addDispatcherFunction()
}