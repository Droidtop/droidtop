AGP 9 now uses built-in Kotlin and the new Android DSL. The launcher keeps its
standalone AGP 8 build compatible, generates Dagger through KSP, and declares
its Kotlin source roots explicitly. Shared Compose versions reach consumers
through the published API platform instead of a global compatibility flag.
