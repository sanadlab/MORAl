package io.github.sanadlab.testkotlin

import io.github.sanadlab.annotations.CacheInvalidate
import io.github.sanadlab.annotations.Memoize

// Kotlin test subject: top-level functions. Kotlin compiles them to static
// methods of the file class TopLevelFunctionsKt.

var slugifyCalls = 0

private var separator = "-"

// The static initializer of the file class calls a memoized function.
val HOME_SLUG: String = slugify("Home Page")

@Memoize
fun slugify(title: String): String {
    slugifyCalls++
    return title.lowercase().replace(" ", separator)
}

@CacheInvalidate("slugify")
fun setSlugSeparator(value: String) {
    separator = value
}

// Bare @CacheInvalidate on a top-level function: flushes every static cache of the file class.
@CacheInvalidate
fun resetSlugs() {
    separator = "-"
    slugifyCalls = 0
}
