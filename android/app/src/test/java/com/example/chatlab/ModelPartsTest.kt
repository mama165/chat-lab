package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPartsTest {
    @Test
    fun indexOfAPart() {
        assertEquals(1, ModelParts.index("vision_model.onnx.part1", "vision_model.onnx"))
        assertEquals(12, ModelParts.index("vision_model.onnx.part12", "vision_model.onnx"))
    }

    @Test
    fun otherFilesAreNotParts() {
        assertNull(ModelParts.index("vision_model.onnx", "vision_model.onnx"))
        assertNull(ModelParts.index("text_model.onnx.part1", "vision_model.onnx"))
        assertNull(ModelParts.index("vision_model.onnx.part1.tmp", "vision_model.onnx"))
        assertNull(ModelParts.index("vision_model.onnx.partx", "vision_model.onnx"))
    }

    @Test
    fun orderedByNumberNotByName() {
        val names = listOf("m.onnx.part10", "m.onnx.part2", "other", "m.onnx.part1", "m.onnx")
        assertEquals(listOf("m.onnx.part1", "m.onnx.part2", "m.onnx.part10"), ModelParts.ordered(names, "m.onnx"))
    }

    @Test
    fun noPartsGivesEmptyList() {
        assertEquals(emptyList<String>(), ModelParts.ordered(listOf("m.onnx"), "m.onnx"))
    }
}
