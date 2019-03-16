/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.kotlinpoet

import java.io.Closeable

/**
 * Implements soft line wrapping on an appendable. To use, append characters using
 * [LineWrapper.append], which will replace spaces with newlines where necessary. Use
 * [LineWrapper.appendNonWrapping] to append a string that never wraps.
 */
internal class LineWrapper(
  private val out: Appendable,
  private val indent: String,
  private val columnLimit: Int
) : Closeable {
  private var closed = false

  /**
   * Segments of the current line to be joined by spaces or wraps. Never empty, but contains a lone
   * empty string if no data has been emitted since the last newline.
   */
  private val segments = mutableListOf("")

  /** Number of indents in wraps. -1 if the current line has no wraps. */
  private var indentLevel = -1

  /** Optional prefix that will be prepended to wrapped lines. */
  private var linePrefix = ""

  /** Emit `s` replacing its spaces with line wraps as necessary. */
  fun append(s: String, indentLevel: Int = -1, linePrefix: String = "") {
    check(!closed) { "closed" }

    var pos = 0
    while (pos < s.length) {
      val c = s[pos]
      when (c) {
        ' ' -> {
          // Each space starts a new empty segment.
          this.indentLevel = indentLevel
          this.linePrefix = linePrefix
          segments += ""
          pos++
        }

        '\n' -> {
          // Each newline emits the current segments.
          newline()
          pos++
        }

        '·' -> {
          // Render · as a non-breaking space.
          segments[segments.size - 1] += " "
          pos++
        }

        in OPENING_BRACKETS, in CLOSING_BRACKETS -> {
          // put an opening/closing bracket into its own segment
          if (segments[segments.size - 1].isEmpty()) {
            segments[segments.size - 1] += c.toString()
          } else {
            segments += c.toString()
          }
          // if the opening bracket was preceded by a space we'll put it into the previous segment
          // to preserve it
          if (c in OPENING_BRACKETS && pos > 0 && s[pos - 1] == ' ') {
            segments[segments.size - 2] += " "
          }
          segments += ""
          pos++
        }

        else -> {
          var next = s.indexOfAny(SPECIAL_CHARACTERS, pos)
          if (next == -1) next = s.length
          segments[segments.size - 1] += s.substring(pos, next)
          pos = next
        }
      }
    }
  }

  /** Emit `s` leaving spaces as-is. */
  fun appendNonWrapping(s: String) {
    check(!closed) { "closed" }
    require(!s.contains("\n"))

    segments[segments.size - 1] += s
  }

  fun newline() {
    check(!closed) { "closed" }

    emitCurrentLine()
    out.append("\n")
    indentLevel = -1
  }

  /** Flush any outstanding text and forbid future writes to this line wrapper.  */
  override fun close() {
    emitCurrentLine()
    closed = true
  }

  private fun emitCurrentLine() {
    foldUnsafeBreaks()
    foldDanglingAndNestedBrackets()

    var start = 0
    var columnCount = segments[0].length
    var openingBracketIndex = if (segments[0] in OPENING_BRACKET_SEGMENTS) 0 else -1
    var startNewLine = false

    for (i in 1 until segments.size) {
      val segment = segments[i]
      val newColumnCount = when (segment) {
        in OPENING_BRACKET_SEGMENTS -> {
          // opening bracket is not followed by a space
          openingBracketIndex = i
          columnCount + segment.length
        }
        in CLOSING_BRACKET_SEGMENTS -> {
          // closing bracket is not preceded by a space so it reclaims space claimed be previous seg
          columnCount - 1 + segment.length
        }
        else -> {
          // other segments are followed by space
          columnCount + 1 + segment.length
        }
      }

      // If this segment doesn't fit in the current run, print the current run and start a new one.
      if (newColumnCount > columnLimit) {
        if (openingBracketIndex == -1) {
          emitSegmentRange(start, i, startNewLine)
          start = i
          columnCount = segment.length + indent.length * indentLevel
          startNewLine = true
          continue
        } else if (segment in CLOSING_BRACKET_SEGMENTS) {
          if (start < openingBracketIndex) {
            emitSegmentRange(start, openingBracketIndex, startNewLine)
          }
          emitSegmentRangeInsideBrackets(openingBracketIndex, i, multiline = true)
          start = i + 1
          columnCount = segment.length + indent.length * indentLevel
          startNewLine = true
          continue
        }
      } else if (openingBracketIndex != -1 && segment in CLOSING_BRACKET_SEGMENTS) {
        if (start < openingBracketIndex) {
          emitSegmentRange(start, openingBracketIndex, startNewLine)
        }
        emitSegmentRangeInsideBrackets(openingBracketIndex, i, multiline = false)
        start = i + 1
        startNewLine = false
        continue
      }

      columnCount = newColumnCount
    }

    // Print the last run.
    emitSegmentRange(start, segments.size, startNewLine)

    segments.clear()
    segments += ""
  }

  private fun emitSegmentRange(startIndex: Int, endIndex: Int, startNewLine: Boolean) {
    // If this is a wrapped line we need a newline and an indent.
    if (startNewLine && segments[startIndex].isNotEmpty()) {
      out.append("\n")
      for (i in 0 until indentLevel) {
        out.append(indent)
      }
      out.append(linePrefix)
    }

    // Emit each segment separated by spaces.
    out.append(segments[startIndex])
    for (i in startIndex + 1 until endIndex) {
      out.append(" ")
      out.append(segments[i])
    }
  }

  private fun emitSegmentRangeInsideBrackets(
    openingBracketIndex: Int,
    closingBracketIndex: Int,
    multiline: Boolean
  ) {
    out.append(segments[openingBracketIndex])
    for (i in openingBracketIndex + 1 until closingBracketIndex) {
      if (multiline) {
        out.append("\n")
        for (j in 0 until indentLevel + 2) {
          out.append(indent)
        }
        out.append(linePrefix)
      }
      out.append(segments[i])
      if (!multiline && i < closingBracketIndex - 1) {
        out.append(' ')
      }
    }
    if (multiline) {
      out.append('\n')
    }
    out.append(segments[closingBracketIndex])
  }

  /**
   * Any segment that starts with '+' or '-' can't have a break preceding it. Combine it with the
   * preceding segment. Note that this doesn't apply to the first segment.
   */
  private fun foldUnsafeBreaks() {
    var i = 1
    while (i < segments.size) {
      val segment = segments[i]
      if (UNSAFE_LINE_START.matches(segment)) {
        segments[i - 1] = segments[i - 1] + " " + segments[i]
        segments.removeAt(i)
        if (i > 1) i--
      } else {
        i++
      }
    }
  }

  /**
   * Only the contents of outer brackets are wrapped, any nested brackets are currently ignored.
   * Also fixup dangling brackets so that there are no spaces in between.
   */
  private fun foldDanglingAndNestedBrackets() {
    var foldFrom = 0
    var foldUntil = segments.size
    // Trying to find a pair of outer brackets:
    // - If found - fold from `openingBracketIndex + 1` until `closingBracketIndex`
    // - If not found - fold from 0 until `segments.size`
    openBracketLoop@ for (i in 0 until segments.size) {
      if (segments[i] in OPENING_BRACKET_SEGMENTS) {
        for (j in segments.size - 1 downTo i + 1) {
          if (segments[j] in CLOSING_BRACKET_SEGMENTS) {
            // found the pair
            foldFrom = i + 1
            foldUntil = j
            break@openBracketLoop
          }
        }
        // couldn't find the closing bracket
        break@openBracketLoop
      }
    }

    var i = foldFrom
    while (i < foldUntil) {
      val segment = segments[i]
      if (segment in OPENING_BRACKET_SEGMENTS || segment in CLOSING_BRACKET_SEGMENTS) {
        var next = i
        while (i + 1 < foldUntil) {
          val nextSegment = segments[i + 1]
          segments[i] += nextSegment
          segments.removeAt(i + 1)
          foldUntil--
          next = i + 1 // move to the next segment
          if (nextSegment !in OPENING_BRACKET_SEGMENTS &&
              nextSegment !in CLOSING_BRACKET_SEGMENTS) {
            break
          }
        }
        if (i > 0) {
          segments[i - 1] += segments[i]
          segments.removeAt(i)
          foldUntil--
          next = i // removed previous segment, i becomes the next
        }
        i = next
      } else {
        i++
      }
    }
  }

  companion object {
    private val UNSAFE_LINE_START = Regex("\\s*[-+][^>]*")
    private val OPENING_BRACKETS = "([".toCharArray()
    private val CLOSING_BRACKETS = ")]".toCharArray()
    private val SPECIAL_CHARACTERS = OPENING_BRACKETS + CLOSING_BRACKETS + " \n·".toCharArray()
    private val OPENING_BRACKET_SEGMENTS = OPENING_BRACKETS.mapTo(HashSet()) { it.toString() }
    private val CLOSING_BRACKET_SEGMENTS = CLOSING_BRACKETS.mapTo(HashSet()) { it.toString() }
  }
}
