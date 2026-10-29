// From k2-fsa/sherpa-onnx v1.13.8 (Apache-2.0, see NOTICE here), sherpa-onnx/kotlin-api/QnnConfig.
// droidtop: unchanged.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
