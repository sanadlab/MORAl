package io.github.sanadlab.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate

/**
 * KSP processor that validates @Memoize annotations at compile time.
 * Does NOT generate code — validation only.
 */
class MemoizeProcessor(private val logger: KSPLogger) : SymbolProcessor {

    companion object {
        private const val MEMOIZE_ANNOTATION = "io.github.sanadlab.annotations.Memoize"
        private val IMMUTABLE_TYPES = setOf(
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
            "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.Char",
            "kotlin.String", "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
            "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character",
            "java.lang.String", "java.math.BigDecimal", "java.math.BigInteger",
            "java.util.UUID",
            "boolean", "byte", "short", "int", "long", "float", "double", "char"
        )
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(MEMOIZE_ANNOTATION)
        val deferred = mutableListOf<KSAnnotated>()

        symbols.forEach { symbol ->
            if (!symbol.validate()) {
                deferred.add(symbol)
                return@forEach
            }

            if (symbol !is KSFunctionDeclaration) {
                logger.error("@Memoize can only be applied to functions", symbol)
                return@forEach
            }

            validateFunction(symbol)
        }

        return deferred
    }

    private fun validateFunction(func: KSFunctionDeclaration) {
        val funcName = func.simpleName.asString()
        val containingClass = func.parentDeclaration?.simpleName?.asString() ?: "<top-level>"

        // Check: must not be abstract
        if (func.isAbstract) {
            logger.error("@Memoize cannot be applied to abstract method '$funcName' in $containingClass", func)
        }

        // Check: must not be inline. Kotlin copies the body of an inline function
        // into each caller, so the callers never reach the instrumented method.
        if (Modifier.INLINE in func.modifiers) {
            logger.error(
                "@Memoize cannot be applied to inline function '$funcName' in $containingClass. " +
                "Kotlin copies the body of an inline function into each caller, so the cache is never used.",
                func
            )
        }

        // Check: must have a non-void/Unit return type
        val returnType = func.returnType?.resolve()
        if (returnType != null) {
            val returnTypeName = returnType.declaration.qualifiedName?.asString()
            if (returnTypeName == "kotlin.Unit" || returnTypeName == "void") {
                logger.error(
                    "@Memoize cannot be applied to void/Unit method '$funcName' in $containingClass. " +
                    "Memoized methods must return a value.",
                    func
                )
            }
        }

        // Check: validate maxSize parameter
        val memoizeAnnotation = func.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == MEMOIZE_ANNOTATION
        }
        val maxSizeArg = memoizeAnnotation.arguments.find { it.name?.asString() == "maxSize" }
        if (maxSizeArg != null) {
            val maxSize = maxSizeArg.value as? Int
            if (maxSize != null && maxSize <= 0) {
                logger.error("@Memoize maxSize must be positive, got $maxSize on '$funcName'", func)
            }
        }

        // Warning: check parameter types for mutability
        func.parameters.forEach { param ->
            val paramType = param.type.resolve()
            val typeName = paramType.declaration.qualifiedName?.asString()
            val hasCacheKeyAnnotation = param.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() ==
                    "io.github.sanadlab.annotations.CacheKey"
            }

            if (typeName != null && typeName !in IMMUTABLE_TYPES && !hasCacheKeyAnnotation) {
                val isDataClass = (paramType.declaration as? KSClassDeclaration)
                    ?.modifiers?.contains(Modifier.DATA) == true

                if (!isDataClass) {
                    logger.warn(
                        "Parameter '${param.name?.asString()}' of type '$typeName' in " +
                        "@Memoize method '$funcName' may be mutable. Consider using @CacheKey " +
                        "to specify which fields to use as cache key, or ensure the type has " +
                        "correct hashCode()/equals().",
                        param
                    )
                }
            }
        }
    }
}
