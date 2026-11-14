package com.kingzcheung.xime.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 11-13：× 不出计算候选；算式断一会再接着打，接不上前面已经上屏的算式。 */
class CalculatorResumeTest {
    private fun typed(vararg keys: String) = CalculatorEngine().apply {
        keys.forEach { if (CalculatorEngine.digitOf(it[0]) != null) handleDigit(it) else handleOperator(it) }
    }

    @Test fun timesAndDivideSignsCalculate() {
        val e = typed("1", "2", "×", "3")
        assertEquals("36", e.getResult())
        assertEquals("12×3 = 36", e.getCandidate())
        assertEquals("12×3=36", e.getFormulaResult())
        assertEquals("4", typed("8", "÷", "2").getResult())
        assertEquals("14", typed("2", "＋", "3", "＊", "4").getResult())
        assertEquals("1", typed("３", "−", "２").getResult())
    }

    @Test fun calculatorKeys() {
        listOf("0", "9", ".", "+", "-", "*", "/", "×", "÷", "＋", "－", "＊", "／", "１", "．").forEach {
            assertTrue(it, CalculatorEngine.isCalculatorKey(it))
        }
        listOf("@", ":", "=", "a", "12", "（").forEach { assertFalse(it, CalculatorEngine.isCalculatorKey(it)) }
    }

    @Test fun routerSendsTimesSignToTheEngine() {
        val engine = CalculatorEngine()
        listOf("6", "×", "7").forEach { routeCalculatorKey(it, isNumberKeyboard = true, isCommonSymbolKeyboard = false, calculatorEngine = engine) }
        assertEquals("42", engine.getResult())
    }

    @Test fun trailingExpressionFromTheEditor() {
        assertEquals("12+5", CalculatorEngine.trailingExpression("总共12+5"))
        assertEquals("17+", CalculatorEngine.trailingExpression("12+5=17+"))
        assertEquals("12×3", CalculatorEngine.trailingExpression("单价 12×3"))
        assertEquals("3+2", CalculatorEngine.trailingExpression("-3+2"))
        assertEquals("", CalculatorEngine.trailingExpression("abc12+3"))
        assertEquals("", CalculatorEngine.trailingExpression("10:30+1"))
        assertEquals("", CalculatorEngine.trailingExpression("你好"))
        assertEquals("", CalculatorEngine.trailingExpression("1+2 "))
        assertEquals("", CalculatorEngine.trailingExpression("+"))
    }

    @Test fun resumeContinuesTheExpressionOnScreen() {
        val e = CalculatorEngine()
        assertTrue(e.resume("12+5+"))
        e.handleDigit("3")
        assertEquals("12+5+3", e.getExpression())
        assertEquals("20", e.getResult())
        assertTrue(e.resume("17+3"))
        assertEquals("20", e.getResult())
        assertTrue(e.resume("１２×３"))
        assertEquals("36", e.getResult())
        assertEquals("１２×３", e.getExpression())
    }

    @Test fun resumeRefusesWhatItCannotReplay() {
        val e = CalculatorEngine()
        assertFalse(e.resume("1.2.3+4"))
        assertEquals("", e.getExpression())
        assertNull(e.getCandidate())
    }
}
