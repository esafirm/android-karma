package stream.nolambda.karma.timetravel.dashboard.editor

import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View.OnKeyListener
import androidx.appcompat.widget.AppCompatEditText
import org.w3c.dom.Document
import stream.nolambda.karma.timetravel.dashboard.utils.iterator
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Custom EditText for syntax highlighting, auto-indent, etc.
 * Credits: https://github.com/vickychijwani/kotlin-koans-android/blob/master/app/src/main/code/me/vickychijwani/kotlinkoans/features/common/CodeEditText.kt
 */
class CodeEditText : AppCompatEditText, CodeEditor {
    private var myTextSize = 13f.sp(context)

    // Syntax highlighter information
    private var tokens = listOf<Token>()
    private var matchingBracket = -1

    // Highlight
    var highlights = mutableListOf<Highlight>()

    // Whether or not the syntax highlighter is currently running
    private var updatingTokens = AtomicBoolean()

    // Whether or not we need to update the tokens AGAIN
    private var flagRefreshTokens = AtomicBoolean()

    private var autoPairBrackets = true
    private var autoIndent = true
    private var indent = "    "

    inner class Highlight(val pos: Int, val len: Int, val paint: Paint)

    private var flagEnter = false
    private var flagTab = false

    //Used to indicate that the code area is currently being updated in such a way that we should not save the change in the undo history
    private var FLAG_NO_UNDO_SNAPSHOT = false

    companion object {
        // Paints that will always be used
        private val lineHighlight by lazy { Paint() }
        private val bracketMatch by lazy { Paint() }
        private val blackPaint by lazy { Paint() }
        private val whitePaint by lazy { Paint() }

        // Lists of styles
        private val styles = HashMap<String, TextPaint>()
        private var syntax = listOf<Keyword>()
        private var syntaxLoaded = AtomicBoolean(false)

        // Default tokens
        val punctuation = "()[]{}\"'?:;,.@".toCharArray()
        val operators = "=+-/*%&|<>".toCharArray()
        val delimiters = punctuation + operators + ' '
        val delimiterPairMap = mapOf('(' to ')', '{' to '}', '[' to ']')
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int)
            : super(context, attrs, defStyleAttr) {
        init(attrs, defStyleAttr)
    }

    fun setupForEditing() {
        commonSetup()
        enableEditing()
    }

    fun setupForViewing() {
        commonSetup()
        disableEditing()
    }

    fun enableEditing() {
        setTextIsSelectable(false)
        isCursorVisible = true
    }

    fun disableEditing() {
        setTextIsSelectable(true)
        isCursorVisible = false
    }

    private fun commonSetup() {
        setupTextListener()
        refreshTextSize()
        flagRefreshTokens()
    }

    private fun init(
        attrs: AttributeSet? = null,
        defStyleAttr: Int? = null,
        defStyleRes: Int? = null
    ) {
        // Process XML attributes
        attrs?.let {
            val a: TypedArray = context.theme.obtainStyledAttributes(
                attrs, intArrayOf(android.R.attr.textSize),
                defStyleAttr ?: 0, defStyleRes ?: 0
            )
            try {
                myTextSize = a.getDimensionPixelSize(0, myTextSize.toInt()).toFloat()
            } finally {
                a.recycle()
            }
        }

        // Config
        autoPairBrackets = true
        autoIndent = true
        val indentSize = 4

        indent = " ".repeat(indentSize)

        //Get rid of extra spacing at the top and bottom
        includeFontPadding = false

        // Don't load the syntax again for each tab
        if (!syntaxLoaded.get()) {
            //Create the line highlight Paint
            lineHighlight.style = Paint.Style.FILL
            lineHighlight.color = 0x66AACCFF

            //Create the bracket match Paint
            bracketMatch.style = Paint.Style.STROKE
            bracketMatch.color = 0xFF000000.toInt()

            //Create the black (default text) paint
            blackPaint.style = Paint.Style.FILL
            blackPaint.color = 0xFF000000.toInt()

            //Create the white (cleared) paint
            whitePaint.style = Paint.Style.FILL
            whitePaint.color = 0xFFFFFFFF.toInt()

            //Load the default syntax
            context.assets.open("default_syntax_colors.xml").use {
                val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val doc = docBuilder.parse(it)
                loadSyntax(doc)
            }
        }
    }

    private var textListener: TextWatcher? = null
    private var keyListener: OnKeyListener? = null

    private fun setupTextListener() {
        // We check for null to prevent re-adding the listeners when the fragment is re-created

        if (textListener == null) {
            textListener = object : TextWatcher {
                private var oldText: String? = null

                override fun afterTextChanged(editable: Editable) {
                    if (oldText == null) {
                        return
                    }
                    // Unfortunately, this appears to be the only way to detect character presses in all situations: reading the text directly...
                    val text = text.toString()
                    // Compare the old text and the new text
                    // TODO: Does this check fail in any corner cases (like mass-text insertion / deletion)?
                    if (text.length == oldText!!.length + 1 && selectionStart > 0) {
                        val pressedChar = text[selectionStart - 1]
                        pressKeys(pressedChar.toString())
                    }
                }

                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                    oldText = text.toString()
                }

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    updateTokens()
                }
            }

            addTextChangedListener(textListener)
        }

        if (keyListener == null) {
            //Detect enter key presses... regardless of whether or not the user is using a hardware keyboard
            keyListener = OnKeyListener { _, _, event ->
                //We only want to check key down events...
                //...otherwise we get two events for every press because we have down and up
                if (event.action != KeyEvent.ACTION_DOWN)
                    return@OnKeyListener false

                //Override default TAB key behavior
//                if (keyCode == KeyEvent.KEYCODE_TAB && PreferenceManager.getDefaultSharedPreferences(context).getBoolean("override_tab", true)) {
//                    if (!FLAG_NO_UNDO_SNAPSHOT) {
//                        flagTab = true
//                    }
//                    text.insert(selectionStart, "  ")
//                    flagTab = false
//
//                    return@OnKeyListener true
//                }

                return@OnKeyListener false
            }

            setOnKeyListener(keyListener)
        }

        updateTokens()
    }

    fun loadSyntax(doc: Document) {
        val styleNodes = doc.getElementsByTagName("style")
        val keywordNodes = doc.getElementsByTagName("keyword")

        for (styleNode in styleNodes) {
            //Parse the style
            val paint = TextPaint(paint)
            val name = styleNode.textContent.trim()
            val color = styleNode.attributes.getNamedItem("color")?.nodeValue ?: "#FF000000"
            val bold = (styleNode.attributes.getNamedItem("bold")?.nodeValue == "true")

            //Build the TextPaint
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor(color)

            //Add the style
            styles[name] = paint
        }

        //Get the list of defined keywords
        val tempSyntax = mutableListOf<Keyword>()

        for (keyword in keywordNodes) {
            //Parse the keyword
            val styleName = keyword.nodeName
            val name = keyword.textContent.trim()

            //If this isn't a valid style, bail out
            styles[styleName]?.let { style ->
                //Add the keyword
                tempSyntax.add(Keyword(name, style))
            }
        }

        syntax = tempSyntax
        syntaxLoaded.set(true)
    }

    fun pressKeys(pressed: String) {
        // Detect the ENTER key
        if (pressed.length == 1 && pressed[0] == '\n' && autoIndent) {
            autoIndent()
        }

        // Automatically add a closing delimiter (if the user has enabled auto-pairing delimiters)
        if (autoPairBrackets && delimiterPairMap.containsKey(pressed[0])) {
            text?.insert(selectionStart, delimiterPairMap[pressed[0]].toString())
            setSelection(selectionStart - 1)
        }
    }

    fun autoIndent() {
        val lastLineNum = currentLine - 1

        //Get the indentation of the previous line
        val lines =
            text.toString().split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var lastLine = ""
        var lastIndent = ""

        //Calculate the indentation of the previous line
        if (lines.isNotEmpty()) {
            lastLine = lines[Math.min(lastLineNum, lines.size - 1)]
            (lastLine.indices)
                .takeWhile { lastLine[it] == ' ' }
                .forEach { lastIndent += ' ' }
        }

        //Determine the last character of the previous line (not counting whitespace)
        var lastChar = ' '
        val trimmedLastLine = lastLine.trim { it <= ' ' }
        if (trimmedLastLine.isNotEmpty()) {
            lastChar = trimmedLastLine[trimmedLastLine.length - 1]
        }

        //Automatically indent
        if (lastChar == '{') {
            if (!FLAG_NO_UNDO_SNAPSHOT) {
                flagEnter = true
            }

            // Force editable
            val text = text ?: error("Editable is null")

            //Automatically increase the indent if this is a new code block
            text.insert(selectionStart, lastIndent + indent)

            //Automatically press enter again so that everything lines up nicely.. This is incredibly hacky...
            //Also make sure that the user has enabled curly brace insertion
            if (text.length > selectionStart && text[selectionStart] == '}' && autoPairBrackets) {
                //Add a newline (the extra space is so that we don't recursively detect a newline; adding at least two characters at once sidesteps this possibility)
                text.insert(selectionStart, "\n$lastIndent ")
                //Move the cursor back (hacky...)
                setSelection(selectionStart - (lastIndent.length + 2))
                //Remove the extra space (see above)
                text.replace(selectionStart + 1, selectionStart + 2, "")
            }

            flagEnter = false
        } else {
            //Regular indentation
            text?.insert(selectionStart, lastIndent)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        updateBracketMatch()
    }

    fun updateBracketMatch() {
        //"{}", "()", "[]" open / close matching
        //This isn't necessarily optimized, but it doesn't seem to need it...

        var caret = selectionStart - 1

        //Make sure there is no text selection...
        if (caret == selectionEnd - 1 && caret > -1) {
            //The character to the left of the cursor
            val left = text?.get(caret)
            //The character that we're searching for
            val other: Char
            //Up or down
            val dir: Int

            //This isn't very elegant...
            when (left) {
                in delimiterPairMap -> {
                    other = delimiterPairMap[left]!!; dir = 1
                }
                in delimiterPairMap.values -> {
                    other = delimiterPairMap.keyFor(left)!!; dir = -1
                }
                else -> {
                    matchingBracket = -1; return
                }
            }

            //Start on the right side (puns!)
            if (dir == 1)
                caret++
            //Or the left...
            if (dir == -1)
                caret--

            matchingBracket = -1

            val text = safeText

            //The total opens / closes
            var dif = 0
            while (caret < text.length && caret > -1) {
                val next = text[caret]

                if (next == other)
                    dif -= 1
                if (next == left)
                    dif += 1

                if (dif < 0) {
                    matchingBracket = caret
                    break
                }

                caret += dir
            }
        }
    }

    fun addHighlight(pos: Int, len: Int, paint: Paint) {
        highlights.add(Highlight(pos, len, paint))
    }

    fun clearHighlights() {
        highlights.clear()
    }

    /**
     * @return the number of the currently selected line
     */
    val currentLine: Int
        get() {
            if (selectionStart > -1)
                return layout.getLineForOffset(selectionStart)

            return -1
        }

    /**
     * Returns the character offset for the specified line.
     * This is related to offsetForLineEnd(int)

     * @param line
     * *
     * @return
     */
    fun offsetForLine(line: Int): Int {
        //Get a list of lines
        val lines =
            text.toString().split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        //Count up to the specified line
        var off = 0
        for (i in 0..Math.min(lines.size, line) - 1)
        //Add the length of each line
            off += lines[i].length + 1

        //We don't want to return values that are too big...
        val text = safeText
        if (off > text.length)
            off = text.length
        //...or to small
        if (off < 0)
            off = 0

        return off
    }

    /**
     * Returns the character offset for the end of the specified line.
     * This is related to offsetForLine(int)

     * @param line
     * *
     * @return
     */
    fun offsetForLineEnd(line: Int): Int {
        //Get a list of lines
        val lines =
            text.toString().split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        //Count up to the specified line, including the specified line
        var off = 0
        for (i in 0..Math.min(lines.size, line + 1) - 1)
        //Add the length of each line
            off += lines[i].length + 1

        //We don't want to return values that are too big
        val text = safeText
        if (off > text.length)
            off = text.length
        //...or to small
        if (off < 0)
            off = 0

        return off
    }

    private val safeText get() = text!!

    fun lineForOffset(offset: Int): Int {
        //Get a list of lines
        val lines =
            text.toString().split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

        var off = 0
        for (i in lines.indices) {
            off += lines[i].length + 1

            if (off > offset) {
                return i
            }
        }

        return lines.size - 1
    }

    override fun onDraw(canvas: Canvas) {
        val lineHeight = lineHeight.toFloat()

        // Draw base text
        super.onDraw(canvas)

        // If the syntax highlighter hasn't run yet...
        // Make sure this doesn't break
//        if (tokens == null) {
//            //Check again
//            invalidate()
//            return
//        }

//        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("syntax_highlight", true)) {
        //ScrollView doesn't like to let us know when it has scrolled...
        //			ScrollView scroller = (ScrollView) ((APDE) context.getApplicationContext()).getEditor().findViewById(R.id.code_scroller);
        val topVis = 0//(int) Math.max(scroller.getScrollY() / getLineHeight() - 1, 0); //inclusive
        val bottomVis =
            lineCount//(int) Math.floor(Math.min((scroller.getScrollY() + scroller.getHeight()) / getLineHeight() + 1, getLineCount())); //exclusive

        for (i in tokens.indices) {
            //Only draw this if we need to
            if (tokens[i].lineNum >= topVis && tokens[i].isCustomPaint)
                tokens[i].display(canvas)
            else if (tokens[i].lineNum > bottomVis)
                break
        }

        //"{}", "()", "[]" open / close matching
        //Make sure we don't crash if the bracket matcher hasn't updated yet and we are deleting a lot of text...
        val text = safeText
        if (matchingBracket != -1 && matchingBracket < text.length) {
            val xOffset =
                compoundPaddingLeft.toFloat() //TODO hopefully no one uses Arabic (right-aligned localities)... because getCompoundPaddingStart() was introduced in a later API level
            val charWidth = paint.measureText("m")

            //Calculate coordinates
            val x = Math.max(xOffset + layout.getPrimaryHorizontal(matchingBracket), 1f)
            val y = lineHeight * layout.getLineForOffset(matchingBracket)

            canvas.drawRect(x, y, x + charWidth, y + lineHeight, bracketMatch)
        }

        val xOffset = compoundPaddingLeft.toFloat()
        val charWidth = paint.measureText("m")

        val radius = 3.dp

        //Draw the highlight boxes
        for (highlight in highlights) {
            if (highlight.pos != -1 && highlight.pos + highlight.len <= text.length) {
                //Calculate coordinates
                val x = Math.max(xOffset + layout.getPrimaryHorizontal(highlight.pos), 1f)
                val y = lineHeight * layout.getLineForOffset(highlight.pos)

                canvas.drawRoundRect(
                    RectF(x, y, x + charWidth * highlight.len, y + lineHeight),
                    radius.toFloat(), radius.toFloat(), highlight.paint
                )
            }
        }
//        }

        //Now that we've multi-threaded the new syntax highlighter, we don't need the old one
        //It's still here in memory...
    }

    /**
     * Call this function to force the tokens to update AGAIN after the current / next update cycle has completed
     */
    fun flagRefreshTokens() {
        if (updatingTokens.get()) {
            flagRefreshTokens.set(true)
        } else {
            updateTokens()
        }
    }

    @Synchronized
    fun updateTokens() {
        //Get the text now so that we don't experience any synchronization issues
        val text = text.toString()

        Thread {
            updatingTokens.set(true)

            val tempTokens = tokenize(text, 0, delimiters)

            for (i in tempTokens.indices) {
                var nextNonSpace = ""
                for (j in i + 1..tempTokens.size - 1) {
                    val next = tempTokens[j].text

                    if (next == " " || next == "\n")
                        continue

                    nextNonSpace = next
                    break
                }

                tempTokens[i].updatePaint(nextNonSpace)
            }

            var multiLineComment = false
            var singleLineComment = false
            var stringLiteral = false
            var charLiteral = false

            var startLiteral = -1

            var prev = ""
            var next: String

            for (i in tempTokens.indices) {
                val token = tempTokens[i]
                next = if (i < tempTokens.size - 1) tempTokens[i + 1].text else ""

                if (token.text == "\n") {
                    singleLineComment = false
                    stringLiteral = false
                    charLiteral = false

                    continue
                }

                if (stringLiteral && prev == "\"" && i > startLiteral + 1)
                    stringLiteral = false

                if (charLiteral && prev == "'" && i > startLiteral + 1)
                    charLiteral = false

                if (!multiLineComment && !singleLineComment && !stringLiteral && !charLiteral) {
                    //Test for single-line comments
                    if (token.text == "/" && next == "/")
                        singleLineComment = true
                    else if (token.text == "/" && next == "*")
                        multiLineComment = true//Test for multi-line comments
                }

                //TODO Implement incomplete / invalid literals

                //Test for String literals
                if (!stringLiteral && !multiLineComment && !singleLineComment && !charLiteral && token.text == "\"") {
                    stringLiteral = true
                    startLiteral = i
                }

                //Test for char literals
                if (!charLiteral && !multiLineComment && !singleLineComment && !stringLiteral && token.text == "'") {
                    charLiteral = true
                    startLiteral = i
                }

                //Change paint for comments and literals
                if (singleLineComment) {
                    token.paint = styles["comment_single"]!!
                    token.isCustomPaint = true
                } else if (multiLineComment) {
                    token.paint = styles["comment_multi"]!!
                    token.isCustomPaint = true
                } else if (stringLiteral) {
                    token.paint = styles["literal_string"]!!
                    token.isCustomPaint = true
                } else if (charLiteral) {
                    token.paint = styles["literal_char"]!!
                    token.isCustomPaint = true
                }

                //Test for end multi-line comments
                if (multiLineComment)
                    if (prev == "*" && token.text == "/")
                        multiLineComment = false

                prev = token.text
            }

            //Compaction of same-colored tokens - like comments, for example (they were causing serious lag)
            //TODO we're probably still picking up space characters...

            val finalTokens = mutableListOf<Token>()
            var activeToken: Token? = null

            for (token in tempTokens) {
                if (activeToken == null) {
                    activeToken = token
                    continue
                }

                //Direct reference comparison of paints, should be OK...
                if (activeToken.lineNum != token.lineNum || activeToken.paint !== token.paint) {
                    finalTokens.add(activeToken)
                    activeToken = token
                    continue
                }

                activeToken.text += token.text
            }

            //Add the extra token at the end
            activeToken?.let {
                finalTokens.add(activeToken)
            }

            tokens = finalTokens

            //If there is no text, wipe the tokens
            //TODO ...why do we need this? It seems somewhat counterproductive...
            if (text.isEmpty()) {
                clearTokens()
            }

            postInvalidate()

            updatingTokens.set(false)

            //Check to see if we have updated the text AGAIN since starting this update
            //We shouldn't get too much recursion...
            if (flagRefreshTokens.get()) {
                updateTokens()
                flagRefreshTokens.set(false)
            }
        }.start()
    }

    //Called internally to get a list of all tokens in an input String, such that each token may be syntax highlighted with a different color
    private fun tokenize(input: String, lineOffset: Int, delimiters: CharArray): List<Token> {
        //Create the output list
        val output = mutableListOf<Token>()
        output.add(Token("", 0, 0))

        var wasDelimiter = false
        var xOff = 0
        var currentLine = 0

        //Read each char in the input String
        for (element in input) {
            if (element == '\n') {
                currentLine++
                xOff = 0

                output.add(Token("\n", xOff, lineOffset + currentLine))
                wasDelimiter = true

                continue
            }

            //If it is a token, split into a new String
            if (isDelimiter(element, delimiters)) {
                output.add(Token("", xOff, lineOffset + currentLine))
                wasDelimiter = true
            } else if (wasDelimiter) {
                output.add(Token("", xOff, lineOffset + currentLine))
                wasDelimiter = false
            }

            //Append the char
            output[output.size - 1].text += element

            xOff++
        }

        return output
    }

    //Called internally from tokenize()
    //Determines whether or not the specified char is in the array of chars
    private fun isDelimiter(token: Char, delimiters: CharArray): Boolean {
        return delimiters.contains(token)
    }

    //Used internally for the syntax highlighter
    private inner class Token(var text: String, var offset: Int, var lineNum: Int) {
        var paint = styles["base"]!!

        //Do we actually we need to draw this over the default text?
        var isCustomPaint = false
        val BASE_PAINT = styles["base"]!!

        fun updatePaint(nextNonSpace: String) {
            val keyword = getKeyword(text)
            paint = when {
                keyword != null -> keyword.paint
                text.length == 1 && text[0] in punctuation -> styles["punctuation"]!!
                text.length == 1 && text[0] in operators -> styles["operator"]!!
                nextNonSpace == "(" || nextNonSpace == "{" -> styles["function"]!!
                else -> BASE_PAINT
            }
            isCustomPaint = (paint != BASE_PAINT)
        }

        fun display(canvas: Canvas) {
            val lineHeight = lineHeight.toFloat()
            val lineOffset =
                (-layout.getLineDescent(0)).toFloat() //AH-HA! This is the metric that we need...
            val xOffset =
                compoundPaddingLeft.toFloat() //TODO hopefully no one uses Arabic (right-aligned localities)... because getCompoundPaddingStart() was introduced in a later API level
            val charWidth = getPaint().measureText("m")

            //Calculate coordinates
            val x = xOffset + offset * charWidth
            val y = lineOffset + lineHeight * (lineNum + 1)

            //Draw highlighted text
            canvas.drawText(text, x, y, paint)
        }

        fun display(canvas: Canvas, customPaint: TextPaint) {
            val lineHeight = lineHeight.toFloat()
            val lineOffset =
                (-layout.getLineDescent(0)).toFloat() //AH-HA! This is the metric that we need...
            val xOffset =
                compoundPaddingLeft.toFloat() //TODO hopefully no one uses Arabic (right-aligned localities)... because getCompoundPaddingStart() was introduced in a later API level
            val charWidth = getPaint().measureText("m")

            //Calculate coordinates
            val x = xOffset + offset * charWidth
            val y = lineOffset + lineHeight * (lineNum + 1)

            //Draw highlighted text
            canvas.drawText(text, x, y, customPaint)
        }

        override fun toString(): String {
            return text
        }
    }

    fun refreshTextSize() {
//        myTextSize = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(context).getString("textsize", "14")).toFloat()
        val scaledTextSize = myTextSize

        setTextSize(TypedValue.COMPLEX_UNIT_PX, myTextSize)

        val styleList = ArrayList(styles.values)

        for (paint in styleList) {
            paint.textSize = scaledTextSize
        }

        for (keyword in syntax) {
            keyword.paint.textSize = scaledTextSize
        }
    }

    fun setUpdateText(text: String) {
        super.setText(text)
    }

    fun setNoUndoText(text: String) {
        FLAG_NO_UNDO_SNAPSHOT = true
        super.setText(text)
        FLAG_NO_UNDO_SNAPSHOT = false
    }

    /**
     * Clear the list of tokens for the syntax highlighter.
     * This function is used when tabs are switched so that the old syntax highlighting doesn't briefly show on top of the new code.
     */
    fun clearTokens() {
        tokens = listOf()

        //Also clear the matching bracket...
        matchingBracket = -1

        //...and also clear the highlight
        clearHighlights()
    }

    fun getKeyword(text: String): Keyword? {
        if (syntaxLoaded.get()) {
            return syntax.firstOrNull { it.name == text }
        }
        return null
    }

    private val Int.dp: Int
        get() = (this * Resources.getSystem().displayMetrics.density).toInt()

    private fun Float.sp(context: Context) =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            this,
            context.resources.displayMetrics
        )

    private fun <K, V> Map<K, V>.keyFor(value: V): K? {
        for ((k, v) in this) {
            if (v == value) {
                return k
            }
        }
        return null
    }

    class Keyword(val name: String, val paint: TextPaint)
}