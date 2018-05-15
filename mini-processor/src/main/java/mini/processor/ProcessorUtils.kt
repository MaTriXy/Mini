package mini.processor

import mini.processor.ProcessorUtils.env
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.type.TypeMirror

object ProcessorUtils {
    lateinit var env: ProcessingEnvironment
}

val Element.isMethod: Boolean get() = this.kind == ElementKind.METHOD

val Element.isClass: Boolean get() = this.kind == ElementKind.CLASS

fun TypeMirror.asElement(): Element = asElementOrNull()!!

fun TypeMirror.asElementOrNull(): Element? = env.typeUtils.asElement(this)

fun Element.getPackageName(): String {
    //xxx.xxx.simpleName
    //xxx.xxx
    val startIndex = toString().count().minus(simpleName.toString().count().plus(1))
    val endIndex = toString().count()
    //simpleName
    return toString().removeRange(startIndex, endIndex)
}
