package io.github.sanadlab.plugin

/**
 * Thrown when the annotations ask for a transform that cannot be correct, for
 * example a static method that invalidates a per-instance cache. A malformed
 * directive is skipped with a warning; this exception must fail the build.
 */
class MemoizeConfigurationException(message: String) : RuntimeException(message)
