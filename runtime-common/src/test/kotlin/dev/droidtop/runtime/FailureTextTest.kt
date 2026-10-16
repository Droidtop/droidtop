package dev.droidtop.runtime

import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FailureTextTest {
    @Test
    fun plainFailureWordsAreShownAsTheyAre() {
        assertEquals("the file could not be opened", FailureText.reason(PlainFailure("the file could not be opened")))
    }

    @Test
    fun backendTextNeverReachesTheSentence() {
        val raw = "java.lang.IllegalStateException: Expected BEGIN_OBJECT at line 1 column 1 path \$"
        listOf(IllegalStateException(raw), IOException(raw), RuntimeException(raw), UnknownHostException(raw)).forEach {
            assertFalse(FailureText.reason(it).contains("BEGIN_OBJECT"))
        }
    }

    @Test
    fun kindsReadPlainly() {
        assertEquals("there is no connection", FailureText.reason(UnknownHostException("api.example")))
        assertEquals("the file could not be found", FailureText.reason(FileNotFoundException("/x")))
        assertEquals("reading or writing failed", FailureText.reason(IOException("EIO")))
    }
}
