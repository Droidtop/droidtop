// From k2-fsa/sherpa-onnx v1.13.8 (Apache-2.0, see NOTICE here), sherpa-onnx/kotlin-api/HomophoneReplacerConfig.
// droidtop: unchanged.
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
