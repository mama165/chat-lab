package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSetupTest {
    @Test
    fun nothingIsMissingWhenEverythingIsThere() {
        assertEquals(emptyList<SetupStep>(), missingSteps(access = true, chatlabChosen = true, modelsFound = true))
    }

    @Test
    fun theAccessToFilesIsListedWhenMissing() {
        assertEquals(listOf(SetupStep.ACCESS), missingSteps(access = false, chatlabChosen = true, modelsFound = true))
    }

    @Test
    fun withoutTheChatlabFolderTheModelsCannotBeFoundEither() {
        assertEquals(
            listOf(SetupStep.CHATLAB, SetupStep.MODELS),
            missingSteps(access = true, chatlabChosen = false, modelsFound = false),
        )
    }

    @Test
    fun aChatlabFolderWithoutModelsIsReported() {
        assertEquals(listOf(SetupStep.MODELS), missingSteps(access = true, chatlabChosen = true, modelsFound = false))
    }

    @Test
    fun afterAReinstallEveryStepIsMissingInOrder() {
        assertEquals(
            listOf(SetupStep.ACCESS, SetupStep.CHATLAB, SetupStep.MODELS),
            missingSteps(access = false, chatlabChosen = false, modelsFound = false),
        )
    }
}
