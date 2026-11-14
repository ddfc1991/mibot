package com.openminis.app.mikova.voice

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceModelLayoutTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(dir: File, name: String) { File(dir, name).writeBytes(byteArrayOf(1)) }

    @Test fun asrNeedsAllThreeTransducerWeights() {
        val dir = temporary.newFolder()
        file(dir, "tokens.txt")
        file(dir, "encoder.int8.onnx")
        assertFalse(VoiceModelManager.validModelDir(dir, VoiceModelKind.ASR))
        file(dir, "decoder.onnx")
        file(dir, "joiner.onnx")
        assertTrue(VoiceModelManager.validModelDir(dir, VoiceModelKind.ASR))
        File(dir, "joiner.onnx").writeBytes(byteArrayOf())
        assertFalse(VoiceModelManager.validModelDir(dir, VoiceModelKind.ASR))
    }

    @Test fun officialTtsTopLevelDirectoryResolvesAndRequiresLexicon() {
        val root = temporary.newFolder()
        val dir = File(root, "vits-icefall-zh-aishell3").apply { mkdirs() }
        file(dir, "tokens.txt")
        file(dir, "model.onnx")
        assertFalse(VoiceModelManager.validModelDir(dir, VoiceModelKind.TTS))
        file(dir, "lexicon.txt")
        assertTrue(VoiceModelManager.validModelDir(dir, VoiceModelKind.TTS))
        assertEquals(dir, VoiceModelManager.resolveModelDir(root, VoiceModelKind.TTS))
    }
}
