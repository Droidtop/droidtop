package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/SPEC.md 3, "Kernel features, detected at runtime" (Droidtop/tracker#555 D10). */
class EngineFeaturesTest {
    private val filesystems = "nodev\tsysfs\nnodev\ttmpfs\n\text4\nnodev\toverlay\nnodev\tcgroup2\n"

    private fun features(vararg states: Pair<EngineFeature, FeatureState>) =
        EngineFeatures("boot", EngineFeature.entries.associateWith { FeatureState(kernel = false) } + states.toMap())

    @Test
    fun `what the kernel has is read from the namespace entries and the filesystems list`() {
        val ns = setOf("mnt", "pid", "uts", "ipc", "net", "cgroup")
        assertTrue(EngineFeatureProbe.kernelHas(EngineFeature.MOUNT_NAMESPACES, ns, filesystems))
        assertTrue(EngineFeatureProbe.kernelHas(EngineFeature.NETWORK_NAMESPACES, ns, filesystems))
        // No "user" entry: a kernel without user namespaces.
        assertFalse(EngineFeatureProbe.kernelHas(EngineFeature.USER_NAMESPACES, ns, filesystems))
        assertTrue(EngineFeatureProbe.kernelHas(EngineFeature.OVERLAYFS, ns, filesystems))
        assertTrue(EngineFeatureProbe.kernelHas(EngineFeature.CGROUPS, ns, filesystems))
        assertFalse(EngineFeatureProbe.kernelHas(EngineFeature.CGROUPS, ns, "nodev\tcgroup\n"))
        assertEquals(setOf("sysfs", "tmpfs", "ext4", "overlay", "cgroup2"), EngineFeatureProbe.filesystemNames(filesystems))
        assertEquals(listOf("cpuset", "cpu", "io", "memory", "pids"), EngineFeatureProbe.parseControllers("cpuset cpu io memory pids\n"))
        assertEquals(emptyList<String>(), EngineFeatureProbe.parseControllers(""))
    }

    @Test
    fun `an option needing a feature names it and why this device cannot give it`() {
        val none = features()
        assertEquals(
            "docker run: --memory needs cgroups, which this device's kernel does not have",
            EngineOptions.refusal("docker run", "--memory", none),
        )
        val rootOnly = features(EngineFeature.CGROUPS to FeatureState(kernel = true, app = false, root = null))
        assertTrue(EngineOptions.refusal("docker run", "--cpus", rootOnly).contains("only with root"))
        val refused = features(EngineFeature.NETWORK_NAMESPACES to FeatureState(kernel = true, app = false, root = false))
        assertTrue(EngineOptions.refusal("docker run", "--network", refused).contains("does not let droidtop use"))
        // Here but not built: said so, never taken and ignored.
        val here = features(EngineFeature.HOSTNAME_NAMESPACES to FeatureState(kernel = true, app = true))
        assertTrue(EngineOptions.refusal("docker run", "--hostname", here).contains("not built in droidtop yet"))
        // Not probed: nothing claimed about the device.
        assertEquals("docker run: --tmpfs is not built in droidtop yet", EngineOptions.refusal("docker run", "--tmpfs", null))
    }

    @Test
    fun `the table knows options with or without their value, and host is what the containers already are`() {
        assertEquals("--memory", EngineOptions.option("--memory=1g"))
        assertEquals("-m", EngineOptions.option("-m"))
        assertNull(EngineOptions.option("--name"))
        assertTrue(EngineOptions.asIs("--network", "host"))
        assertFalse(EngineOptions.asIs("--network", "none"))
        assertFalse(EngineOptions.takesValue("--read-only"))
        assertTrue(EngineOptions.mentioned(listOf("run", "--cpus", "2", "alpine")))
        assertFalse(EngineOptions.mentioned(listOf("run", "alpine")))
    }

    @Test
    fun `docker info says each feature in plain words, and the record survives a round trip`() {
        val f = EngineFeatures(
            "b1",
            EngineFeature.entries.associateWith { FeatureState(kernel = false) } + mapOf(
                EngineFeature.USER_NAMESPACES to FeatureState(kernel = true, app = true),
                EngineFeature.CGROUPS to FeatureState(kernel = true, app = false, root = true),
            ),
            cgroupControllers = listOf("cpu", "memory"),
        )
        val lines = EngineOptions.describe(f)
        assertTrue(lines.contains(" user namespaces: available"))
        assertTrue(lines.contains(" cgroups: available with root (controllers: cpu memory)"))
        assertTrue(lines.contains(" overlayfs: not in this device's kernel"))
        assertEquals(f, EngineFeatures.decode(f.encode()))
        assertNull(EngineFeatures.decode("not json"))
    }
}
