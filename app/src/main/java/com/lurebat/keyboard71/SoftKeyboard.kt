@file:Suppress("NAME_SHADOWING")

package com.lurebat.keyboard71

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.jormy.nin.NINLib.onChangeAppOrTextbox
import com.jormy.nin.NINLib.onExternalSelChange
import com.jormy.nin.NINLib.onTextSelection
import com.jormy.nin.NINLib.onTouchEvent as nativeOnTouchEvent
import com.jormy.nin.NINLib.onWordDestruction
import com.jormy.nin.NINLib.init as nativeInit
import com.lurebat.keyboard71.BuildConfig
import com.lurebat.keyboard71.tasker.triggerBasicTaskerEvent
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min


class SoftKeyboard : InputMethodService() {
    private var startedRetype: Boolean = false
    val textBoxEventQueue: ConcurrentLinkedQueue<TextBoxEvent> = ConcurrentLinkedQueue()
    val textOpQueue: ConcurrentLinkedQueue<TextOp> = ConcurrentLinkedQueue()
    private var lazyString: LazyString = LazyStringRope(
        SimpleCursor(0,0),
        SimpleCursor(-1, -1),
        "",
        "",
        "",
        InputConnectionRefresher(){ currentInputConnection }
    )
    private var didProcessTextOps = false
    private var lastTextOpTimeMillis: Long = 0
    private var selectionDiffRetype: SimpleCursor? = null
    private var candidateLocationBeforeRetype: SimpleCursor? = null
    private var afterRetypeCounter = 0

    private var selectionMode = false
    private var fallbackKeyboardView: View? = null
    private var hybridKeyboardView: View? = null

    override fun onCreate() {
        super.onCreate()
        keyboard = this
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        val shouldSignal =
            !didProcessTextOps && System.currentTimeMillis() - lastTextOpTimeMillis >= 55
        if (BuildConfig.DEBUG) {
            Log.d("NIN", "[before $lazyString, $oldSelStart, $oldSelEnd, $newSelStart, $newSelEnd, $candidatesStart, $candidatesEnd]")
        }
        changeSelection(
            currentInputConnection,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
            if (shouldSignal) "external" else null
        )
        if (!shouldSignal) {
            didProcessTextOps = false
        }

        if (BuildConfig.DEBUG) {
            Log.d("NIN", "[after $lazyString]")
        }
    }

    override fun onCreateInputView(): View {
        if (BuildConfig.STUB_NATIVE_ENGINE) {
            val existing = fallbackKeyboardView
            if (existing != null) {
                (existing.parent as ViewGroup?)?.removeView(existing)
                return existing
            }
            val created = createFallbackKeyboardView()
            fallbackKeyboardView = created
            return created
        }

        val view = ninView
        if (view == null) {
            ninView = NINView(this)
        } else {
            (view.parent as ViewGroup?)?.removeView(view)
        }

        if (BuildConfig.NATIVE_ASSIST_KEYS) {
            val existingHybrid = hybridKeyboardView
            if (existingHybrid != null) {
                (existingHybrid.parent as ViewGroup?)?.removeView(existingHybrid)
                return existingHybrid
            }
            val hybrid = createHybridKeyboardView(ninView!!)
            hybridKeyboardView = hybrid
            return hybrid
        }

        return ninView!!
    }

    private fun createHybridKeyboardView(nativeView: View): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }

        // Stack native renderer above assist keys so both are usable during migration.
        (nativeView.parent as? ViewGroup)?.removeView(nativeView)
        root.addView(
            nativeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            createAssistKeyboardView(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return root
    }

    private fun createFallbackKeyboardView(): View {
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            resources.displayMetrics
        ).toInt()
        val keyGap = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            4f,
            resources.displayMetrics
        ).toInt()
        val keyHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            52f,
            resources.displayMetrics
        ).toInt()
        val keyCornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            12f,
            resources.displayMetrics
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#141A23"))
        }

        val banner = TextView(this).apply {
            text = "Keyboard 71 • migration mode"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E6B656"))
            textSize = 14f
            setPadding(pad, pad / 2, pad, pad)
        }
        root.addView(banner)

        fun keyBackground(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = keyCornerRadius
                setColor(Color.parseColor("#2B3341"))
                setStroke(1, Color.parseColor("#3A4558"))
            }
        }

        fun createKey(label: String, weight: Float): Button {
            return Button(this).apply {
                text = label
                isAllCaps = false
                textSize = if (label.length <= 2) 22f else 18f
                setTextColor(Color.WHITE)
                background = keyBackground()
                minHeight = keyHeight
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    weight
                ).apply {
                    setMargins(keyGap, keyGap, keyGap, keyGap)
                }

                if (label == "BKSP") {
                    var downX = 0f
                    var lastStep = 0
                    var wordDeleted = false
                    val charStepPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        8f,
                        resources.displayMetrics
                    )
                    val wordThresholdPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        56f,
                        resources.displayMetrics
                    )

                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                lastStep = 0
                                wordDeleted = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = downX - event.x
                                val ic = currentInputConnection

                                if (!wordDeleted && dx >= wordThresholdPx && ic != null) {
                                    if (!deleteWordBeforeCursor(ic)) {
                                        ic.deleteSurroundingText(1, 0)
                                    }
                                    wordDeleted = true
                                    return@setOnTouchListener true
                                }

                                if (!wordDeleted && dx >= charStepPx && ic != null) {
                                    val step = (dx / charStepPx).toInt()
                                    val toDelete = step - lastStep
                                    if (toDelete > 0) {
                                        repeat(toDelete) { ic.deleteSurroundingText(1, 0) }
                                        lastStep = step
                                    }
                                    return@setOnTouchListener true
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!wordDeleted && lastStep == 0) {
                                    onFallbackKeyPress(label)
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                wordDeleted = false
                                lastStep = 0
                            }
                        }
                        true
                    }
                } else {
                    setOnClickListener { onFallbackKeyPress(label) }
                }
            }
        }

        fun addSpacer(row: LinearLayout, weight: Float) {
            row.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, weight)
                }
            )
        }

        fun addRow(keys: List<Pair<String, Float>>, leftSpacer: Float = 0f, rightSpacer: Float = 0f) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            if (leftSpacer > 0f) addSpacer(row, leftSpacer)
            for ((key, weight) in keys) {
                row.addView(createKey(key, weight))
            }
            if (rightSpacer > 0f) addSpacer(row, rightSpacer)
            root.addView(row)
        }

        addRow(listOf("q","w","e","r","t","y","u","i","o","p").map { it to 1f })
        addRow(listOf("a","s","d","f","g","h","j","k","l").map { it to 1f }, leftSpacer = 0.4f, rightSpacer = 0.4f)
        addRow(listOf("z","x","c","v","b","n","m",",",".").map { it to 1f }, leftSpacer = 0.9f, rightSpacer = 0.9f)
        addRow(
            listOf(
                "BKSP" to 1.4f,
                "UNDO" to 1.4f,
                "SPACE" to 3.6f,
                "ENTER" to 1.6f
            )
        )
        return root
    }

    private fun createAssistKeyboardView(): View {
        val assist = createFallbackKeyboardView()
        (assist as? LinearLayout)?.let { layout ->
            if (layout.childCount > 0 && layout.getChildAt(0) is TextView) {
                (layout.getChildAt(0) as TextView).text = "Assist Keys (Native migration)"
            }
            for (i in 0 until layout.childCount) {
                val child = layout.getChildAt(i)
                child.setOnTouchListener { touchedView, event ->
                    relayNativeTouchFromAssist(layout, event)
                    false
                }
            }
        }
        return assist
    }

    private fun relayNativeTouchFromAssist(root: View, event: MotionEvent) {
        val action = NINView.actionToJormyAction(event.actionMasked)
        if (action == -1) {
            return
        }

        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val x = event.rawX - rootLocation[0]
        val y = event.rawY - rootLocation[1]

        val width = maxOf(root.width, 1)
        val height = maxOf(root.height, 1)
        nativeInit(width, height, width, height)
        nativeOnTouchEvent(
            0,
            action,
            x,
            y,
            event.pressure,
            event.size,
            System.currentTimeMillis()
        )
    }

    private fun onFallbackKeyPress(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "BKSP" -> ic.deleteSurroundingText(1, 0)
            "UNDO" -> sendCtrlZ(ic)
            "SPACE" -> ic.commitText(" ", 1)
            "ENTER" -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)).also {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            else -> ic.commitText(key, 1)
        }
    }

    private fun sendCtrlZ(ic: InputConnection) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(
            KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_Z,
                0,
                KeyEvent.META_CTRL_ON
            )
        )
        ic.sendKeyEvent(
            KeyEvent(
                now,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_Z,
                0,
                KeyEvent.META_CTRL_ON
            )
        )
    }

    private fun deleteWordBeforeCursor(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(128, 0) ?: return false
        if (before.isEmpty()) return false

        val trimmedTrailing = before.trimEnd()
        val trailingWhitespace = before.length - trimmedTrailing.length
        val core = trimmedTrailing
        val word = core.takeLastWhile { !it.isWhitespace() }
        val toDelete = trailingWhitespace + word.length
        if (toDelete <= 0) return false

        ic.deleteSurroundingText(toDelete, 0)
        return true
    }

    override fun onStartInputView(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)

        Log.d("SoftKeyboard",
            "------------ jormoust Editor Info : ${attribute.packageName} | ${attribute.fieldName}|${attribute.inputType}"
        )
        var keyboardType = ""

        when (attribute.inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_TEXT -> {
                val variation = attribute.inputType and EditorInfo.TYPE_MASK_VARIATION
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI || variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
                    keyboardType = "uri"
                }
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD) {
                    keyboardType = "passwd"
                }
            }
        }

        doTextEvent(
            TextBoxEvent.AppFieldChange(attribute.packageName, attribute.fieldName, keyboardType)
        )

        lazyString = LazyStringRope(
            SimpleCursor(attribute.initialSelStart, attribute.initialSelEnd),
            SimpleCursor(-1, -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) attribute.getInitialTextBeforeCursor(1000, 0) else currentInputConnection.getTextBeforeCursor(1000, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) attribute.getInitialSelectedText(0) else currentInputConnection.getSelectedText(0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) attribute.getInitialTextAfterCursor(1000, 0) else currentInputConnection.getTextAfterCursor(1000, 0),
            InputConnectionRefresher(){ currentInputConnection }
        )

        signalCursorCandidacyResult(currentInputConnection, "startInputView")
    }

    @Deprecated("Deprecated in Java")
    override fun onViewClicked(focusChanged: Boolean) {
        val curconn = currentInputConnection
        if (curconn != null) {
            curconn.setComposingRegion(-1, -1)
            signalCursorCandidacyResult(curconn, "onViewClicked")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyUp(keyCode, event)
    }
        private fun changeSelection(
            ic: InputConnection,
            selStart: Int? = null,
            selEnd: Int? = null,
            candidatesStart: Int? = null,
            candidatesEnd: Int? = null,
            signal: String? = null
        ) {
            lazyString.setSelectionAndCandidate(selStart, selEnd, candidatesStart, candidatesEnd)
            if (signal != null) {
                signalCursorCandidacyResult(ic, signal)
            }
        }

        private fun performSetSelection(
            selectStart: Int,
            selectEnd: Int,
            fromStart: Boolean,
            signal: Boolean,
            ic: InputConnection,
        ) {

            var shouldSignal = !fromStart || signal

            var keepSelection = false

            var keepCandidate = false

            var start = selectStart
            var end = selectEnd
            val isNormalMovement = start == end && !fromStart && !signal

            var finalSelectionStart = 0
            var finalSelectionEnd: Int

            val isAfterRetype = selectStart == 5000 && selectEnd == 5000 && !fromStart && !signal
            if (isAfterRetype) {
                afterRetypeCounter += 1
                if (afterRetypeCounter < 3) {
                    return
                }
                afterRetypeCounter = 0
                val deltaSelection = selectionDiffRetype ?: SimpleCursor(0, 0)

                val candidateStart = candidateLocationBeforeRetype?.let {
                    candidateLocationBeforeRetype = null
                    if (it.start == -1 || it.end == -1) {
                        SimpleCursor(-1, -1)
                    } else {
                        SimpleCursor(lazyString.selection.end + it.start , lazyString.selection.end + it.end)
                    }
                } ?: SimpleCursor(0, 0)

                lazyString.candidate.set(candidateStart.start, candidateStart.end)
                finalSelectionStart = deltaSelection.start
                finalSelectionEnd = deltaSelection.end
                keepCandidate = false // Sadly signaling messes up the candidate, if we find a way to avoid signaling - we can turn this on
                keepSelection = true
            } else {
                val candidate = lazyString.getCandidate()
                if (candidate != null && fromStart) {
                    val candidate = lazyString.getCandidate()
                    if (candidate != null) {
                        val candidateBeforeSelection = candidate.substring(0, min(candidate.length, lazyString.selection.min - lazyString.candidate.min)).toByteArray().size
                        if (candidateBeforeSelection > 0) {
                            start -= candidateBeforeSelection
                            end -= candidateBeforeSelection
                        }
                    }
                }

                if ((isNormalMovement && selectionMode)) {
                    keepSelection = true
                    finalSelectionEnd = lazyString.byteOffsetToGraphemeOffset(lazyString.selection.end, end)
                } else {
                    finalSelectionStart = lazyString.byteOffsetToGraphemeOffset(lazyString.selection.start, start)
                    finalSelectionEnd = lazyString.byteOffsetToGraphemeOffset(lazyString.selection.end,-lazyString.selection.length() + end)
                }



                if (!startedRetype) {
                    // get the selection before retype
                    selectionDiffRetype = SimpleCursor(lazyString.selection.start, lazyString.selection.end)
                    candidateLocationBeforeRetype = SimpleCursor(lazyString.candidate.start, lazyString.candidate.end)
                }


            }


            lazyString.moveSelection(finalSelectionStart, finalSelectionEnd)

            if (fromStart || start != end) {
                keepSelection = true
            }

            ic.setComposingRegion(if (keepCandidate) lazyString.candidate.start else lazyString.candidate.end, lazyString.candidate.end)
            ic.setSelection(if (keepSelection) lazyString.selection.start else lazyString.selection.end, lazyString.selection.end)
            if (!startedRetype) {
                selectionDiffRetype = SimpleCursor(selectionDiffRetype!!.start - lazyString.selection.end, selectionDiffRetype!!.end - lazyString.selection.end)
                candidateLocationBeforeRetype = SimpleCursor(candidateLocationBeforeRetype!!.start - lazyString.selection.end, candidateLocationBeforeRetype!!.end - lazyString.selection.end)
            }

            if (startedRetype) {
                startedRetype = false
            }

            if (shouldSignal) {
                signalCursorCandidacyResult(ic, "setselle")
            }
        }

        private fun performBackReplacement(
            rawBackIndex: Int,
            original: String,
            replacement: String?,
            ic: InputConnection
        ) {
            var backIndexBytes = rawBackIndex

            // For some reason it only skips the candidate if the selection is at the end of the candidate
            if (lazyString.selection.max == lazyString.candidate.max) {
                val candidate = lazyString.getCandidate()
                if (candidate != null) {
                    val candidateBeforeSelection = candidate.substring(0, min(candidate.length, lazyString.selection.min - lazyString.candidate.min)).toByteArray().size
                    if (candidateBeforeSelection > 0) {
                        backIndexBytes += candidateBeforeSelection
                    }
                }
            }

            val stringUntilCursor = lazyString.getStringByBytesBeforeCursor(backIndexBytes)
            val replacement = replacement ?: ""
            var startIndex = lazyString.selection.min - stringUntilCursor.length
            val replacingMiddleOfWord = stringUntilCursor.length < original.length

            lazyString.delete(startIndex, startIndex + original.length)
            lazyString.addString(startIndex, replacement)

            // Delete the original text
            ic.setSelection(startIndex, startIndex)
            ic.setComposingRegion(startIndex, startIndex)
            ic.deleteSurroundingText(0, original.length)

            // Insert the replacement text
            ic.commitText(replacement, 1)
            ic.setComposingRegion(lazyString.candidate.start, lazyString.candidate.end)
            ic.setSelection(lazyString.selection.start, lazyString.selection.end)
            if (replacingMiddleOfWord) {
                signalCursorCandidacyResult(ic, "backrepl")
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun performCursorMovement(
            xmove: Int,
            ymove: Int,
            selectionMode: Boolean,
            ic: InputConnection
        ) {
            lazyString.moveSelection(xmove, xmove)
            if (!selectionMode) {
                lazyString.setSelection(lazyString.selection.end, lazyString.selection.end)
            }

            ic.setComposingRegion(lazyString.selection.start, lazyString.selection.end)
            ic.setSelection(lazyString.selection.start, lazyString.selection.end)
            changeSelection(ic, lazyString.selection.start, lazyString.selection.end, lazyString.candidate.start, lazyString.candidate.end, "cursordrag")
        }

        private fun signalCursorCandidacyResult(ic: InputConnection?, mode: String?) {
            if (ic == null) {
                doTextEvent(TextBoxEvent.Reset)
                return
            }
            if (lazyString.candidate.isNotEmpty() && (lazyString.selection.max < lazyString.candidate.min || lazyString.selection.min > lazyString.candidate.max)) {
                // we jumped out of the candidate range - reset it
                ic.finishComposingText()
                lazyString.candidate.start = -1
                lazyString.candidate.end = -1
            }

            val charCount = 100
            val candidate = lazyString.getCandidate()?.toString()
            val beforeCandidate = if (candidate != null) lazyString.getStringByIndex(maxOf(0, lazyString.candidate.min - charCount), lazyString.candidate.min) else lazyString.getCharsBeforeCursor(charCount).toString()
            val afterCandidate = if (candidate != null) lazyString.getStringByIndex(lazyString.candidate.max, lazyString.candidate.max + charCount) else lazyString.getCharsAfterCursor(charCount).toString()

            doTextEvent(TextBoxEvent.Selection(candidate ?: "", beforeCandidate, afterCandidate, mode))
        }

        fun relayDelayedEvents() {
            while (true) {
                val event = textBoxEventQueue.poll()
                if (event != null && BuildConfig.DEBUG) {
                    Log.d("NIN", "Relaying delayed event: $event")
                }
                when (event) {
                    is TextBoxEvent.AppFieldChange -> onChangeAppOrTextbox(
                        event.packageName,
                        event.field,
                        event.mode
                    )

                    TextBoxEvent.Reset -> onExternalSelChange()

                    is TextBoxEvent.Selection -> onTextSelection(
                        event.textBefore,
                        event.currentWord,
                        event.textAfter,
                        event.mode
                    )

                    is TextBoxEvent.WordDestruction -> onWordDestruction(
                        event.destroyedWord,
                        event.destroyedString
                    )

                    null -> break
                }
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun performMUCommand(
            cmd: String?,
            a1: String?,
            a2: String?,
            a3: String?,
            ic: InputConnection
        ) {
            if (cmd == "retypebksp") {
                ic.setComposingText("", 0)
                changeSelection(ic, lazyString.selection.start, lazyString.selection.start, lazyString.selection.start, lazyString.selection.start, null)
                startedRetype = true
            }
        }

        private fun performBackspacing(mode: String?, singleCharacterMode: Boolean, ic: InputConnection) {
            val (min, max) = if (lazyString.selection.isNotEmpty()) {
                Pair(lazyString.selection.min, lazyString.selection.max)
            }
            else if (lazyString.candidate.isNotEmpty()) {
                Pair(lazyString.candidate.min, lazyString.candidate.max)
            } else {
                var singleCharacterMode = singleCharacterMode
                var count = 1;
                when(mode) {
                    // deletes punctuation
                    "S" -> {Pair(lazyString.findCharBeforeCursor(charArrayOf('\n', '.', '!', ',', '?')), lazyString.selection.min)}
                    // Deletes line
                    "L" -> {Pair(lazyString.findCharBeforeCursor(charArrayOf('\n')), lazyString.selection.min)}
                    // does nothing
                    "C", ". " -> {Pair(lazyString.selection.min, lazyString.selection.min)}
                    "emjf" -> {
                        val word = lazyString.getWordsBeforeCursor(1).takeIf { it.isNotBlank() } ?: lazyString.getWordsBeforeCursor(2)
                        Pair(lazyString.selection.min - word.length, lazyString.selection.min)
                    }
                    else -> {
                        if (mode != null && mode.startsWith("X:")) {
                            count = mode.substring(2).toInt()
                        }
                        if (mode == "emjf") {
                            singleCharacterMode = false
                        }

                        val method = if(singleCharacterMode) lazyString::getCharsBeforeCursor else lazyString::getWordsBeforeCursor
                        Pair(lazyString.selection.min - method(count).length, lazyString.selection.min)
                    }
                }
            }

            val length = max - min

            ic.setComposingRegion(min, min)
            ic.setSelection(min, min)
            ic.deleteSurroundingText(0, length)

            lazyString.delete(min, max)

            changeSelection(ic, min, min, -1, -1, mode ?: "setselle")
        }

        fun processTextOps() {
            val buffer = textOpQueue
            val bufferSize = buffer.size
            if (bufferSize == 0) return

            val ic = keyboard?.currentInputConnection ?: return

            ic.beginBatchEdit()

            try {
                for (i in 0 until bufferSize) {
                    checkIfWeirdDelete(textOpQueue)
                    val op = textOpQueue.poll() ?: break
                    val next = textOpQueue.peek()

                    processOperation(op, next, ic)
                }
            } finally {
                didProcessTextOps = true
                lastTextOpTimeMillis = System.currentTimeMillis()
                ic.endBatchEdit()
            }
        }

    private fun checkIfWeirdDelete(textOpQueue: ConcurrentLinkedQueue<TextOp>) {
        if (textOpQueue.size != 3) {
            return
        }

        val first = textOpQueue.poll()
        val second = textOpQueue.poll()
        val third = textOpQueue.poll()

        if (first is TextOp.SetSelection && !first.fromStart && first.signal &&
            second is TextOp.MarkLiquid && second.newString == "" &&
            third is TextOp.SetSelection && third.start == 0 && third.end == 0 && !third.fromStart && !third.signal) {
            if (BuildConfig.DEBUG) {
                Log.d("NIN", "this is a weird delete")
            }
            doTextOp(TextOp.SimpleBackspace(false));
            return;
        }

        textOpQueue.add(first)
        textOpQueue.add(second)
        textOpQueue.add(third)
    }

    private fun processOperation(
            op: TextOp,
            next: TextOp?,
            ic: InputConnection
        ) {
            if (BuildConfig.DEBUG) {
                Log.d("NIN", "processOperation: $op, next: $next")
            }
            when (op) {
                is TextOp.MarkLiquid -> {
                    if (next !is TextOp.MarkLiquid && next !is TextOp.Solidify) {
                        ic.setComposingText(op.newString, 1)
                    }
                }

                is TextOp.Solidify -> {
                    when {
                        op.newString == "\n" -> {
                            var action = 1
                            if (currentInputEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
                                action =
                                    currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                            }
                            if (action != 1) {
                                ic.performEditorAction(action)
                            } else {
                                ic.commitText(op.newString, 1)
                            }
                        }

                        op.newString.startsWith("<{") && op.newString.endsWith("}>") -> {
                            val inner = op.newString.substring(2, op.newString.length - 2)
                            if (inner.isNotEmpty()) {
                                changeSelection(
                                    ic,
                                    signal="external"
                                )
                                doTextOp(TextOp.Special(inner))
                            }
                        }

                        else -> {
                            ic.commitText(op.newString, 1)
                        }
                    }
                }

                is TextOp.SetSelection -> performSetSelection(
                    op.start,
                    op.end,
                    op.fromStart,
                    op.signal,
                    ic
                )

                is TextOp.BackspaceReplacement -> performBackReplacement(
                    op.backIndexFromCursorBytes,
                    op.oldString,
                    op.newString,
                    ic
                )

                is TextOp.SimpleBackspace -> performBackspacing(
                    null,
                    op.singleCharacterMode,
                    ic
                )

                is TextOp.BackspaceModed -> performBackspacing(
                    op.mode,
                    true,
                    ic
                )

                is TextOp.DragCursorUp -> {}
                is TextOp.RequestSelection -> signalCursorCandidacyResult(
                    ic,
                    "requestsel"
                )

                is TextOp.MuCommand -> performMUCommand(
                    op.command,
                    op.arg1,
                    op.arg2,
                    op.arg3,
                    ic
                )

                is TextOp.DragCursorMove -> performCursorMovement(
                    op.xMovement,
                    op.xMovement,
                    op.selectionMode,
                    ic
                )

                is TextOp.Special -> parseSpecialText(ic, op.args)
            }
        }

        private fun parseSpecialText(ic: InputConnection, args: String) {
            val first = args[0]
            val rest = args.substring(1).trim()
            val parts = rest.let {
                val temp = it.split("\\|".toRegex()).toTypedArray()
                if (temp.isEmpty()) {
                    arrayOf(it)
                } else {
                    temp
                }
            }

            when (first) {
                'k' -> {
                    val code = WordHelper.parseKeyCode(parts[0])
                    val modifiers = if (parts.size > 1) WordHelper.parseModifiers(parts[1]) else 0
                    val repeat = if (parts.size > 2) parts[2].toInt() else 0
                    val flags =
                        if (parts.size > 3) parts[3].toInt() else KeyEvent.FLAG_SOFT_KEYBOARD
                    keyDownUp(ic, code, modifiers, repeat, flags)
                }

                'c' -> ic.performContextMenuAction(
                    when (parts[0].uppercase()) {
                        "0", "CUT" -> android.R.id.cut
                        "1", "COPY" -> android.R.id.copy
                        "2", "PASTE" -> android.R.id.paste
                        "3", "SELECT_ALL" -> android.R.id.selectAll
                        "4", "START_SELECT" -> android.R.id.startSelectingText
                        "5", "STOP_SELECT" -> android.R.id.stopSelectingText
                        "6", "SWITCH_KEYBOARD" -> android.R.id.switchInputMethod
                        else -> parts[0].toInt()
                    }
                )

                's' -> selectionMode = !selectionMode

                't' -> this.triggerBasicTaskerEvent(
                    rest,
                    lazyString.getCharsBeforeCursor(1000).toString(),
                    lazyString.getCharsAfterCursor(1000).toString()
                )
            }
        }

    companion object {
        var keyboard: SoftKeyboard? = null
        var ninView: NINView? = null

        fun doTextOp(op: TextOp) = keyboard?.let{ k ->
            k.textOpQueue.let {
                it.add(op)
                Handler(Looper.getMainLooper()).post{ k.processTextOps()}
            }
        }

        fun doTextEvent(event: TextBoxEvent) = keyboard?.textBoxEventQueue?.add(event)

        private fun keyDownUp(
            ic: InputConnection,
            keyEventCode: Int,
            modifiers: Int,
            repeat: Int,
            flags: Int
        ) {
            val eventTime = SystemClock.uptimeMillis()
            ic.sendKeyEvent(
                KeyEvent(
                    eventTime,
                    eventTime,
                    KeyEvent.ACTION_DOWN,
                    keyEventCode,
                    repeat,
                    modifiers,
                    -1,
                    0,
                    flags
                )
            )
            ic.sendKeyEvent(
                KeyEvent(
                    eventTime,
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    keyEventCode,
                    repeat,
                    modifiers,
                    -1,
                    0,
                    flags
                )
            )
        }
    }
}
