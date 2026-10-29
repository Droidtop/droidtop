// From k2-fsa/sherpa-onnx v1.13.8 (Apache-2.0, see NOTICE here), sherpa-onnx/kotlin-api/FeatureConfig.
// droidtop: unchanged.
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
