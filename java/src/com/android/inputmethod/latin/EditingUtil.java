/*
 * Copyright (C) 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.latin;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.regex.Pattern;

/**
 * Utility methods to deal with editing text through an InputConnection.
 */
public class EditingUtil {
    /**
     * Number of characters we want to look back in order to identify the previous word
     */
    private static final int LOOKBACK_CHARACTER_NUM = 15;

    private EditingUtil() {};

    /**
     * Append newText to the text field represented by connection.
     * The new text becomes selected.
     */
    public static void appendText(InputConnection connection, String newText) {
        if (connection == null) {
            return;
        }

        // Commit the composing text
        connection.finishComposingText();

        // Add a space if the field already has text.
        CharSequence charBeforeCursor = connection.getTextBeforeCursor(1, 0);
        if (charBeforeCursor != null
                && !charBeforeCursor.equals(" ")
                && (charBeforeCursor.length() > 0)) {
            newText = " " + newText;
        }

        connection.setComposingText(newText, 1);
    }

    private static int getCursorPosition(InputConnection connection) {
        ExtractedText extracted = connection.getExtractedText(
            new ExtractedTextRequest(), 0);
        if (extracted == null) {
          return -1;
        }
        return extracted.startOffset + extracted.selectionStart;
    }

    private static int getSelectionEnd(InputConnection connection) {
        ExtractedText extracted = connection.getExtractedText(
            new ExtractedTextRequest(), 0);
        if (extracted == null) {
          return -1;
        }
        return extracted.startOffset + extracted.selectionEnd;
    }

    /**
     * @param connection connection to the current text field.
     * @param sep characters which may separate words
     * @return the word that surrounds the cursor, including up to one trailing
     *   separator. For example, if the field contains "he|llo world", where |
     *   represents the cursor, then "hello " will be returned.
     */
    public static String getWordAtCursor(
            InputConnection connection, String separators) {
        return getWordAtCursor(connection, separators, null);
    }

    /**
     * @param connection connection to the current text field.
     * @param sep characters which may separate words
     * @return the word that surrounds the cursor, including up to one trailing
     *   separator. For example, if the field contains "he|llo world", where |
     *   represents the cursor, then "hello " will be returned.
     */
    public static String getWordAtCursor(
        InputConnection connection, String separators, Range range) {
        Range r = getWordRangeAtCursor(connection, separators, range);
        return (r == null) ? null : r.word;
    }

    /**
     * Removes the word surrounding the cursor. Parameters are identical to
     * getWordAtCursor.
     */
    public static void deleteWordAtCursor(
        InputConnection connection, String separators) {

        Range range = getWordRangeAtCursor(connection, separators, null);
        if (range == null) return;

        connection.finishComposingText();
        // Move cursor to beginning of word, to avoid crash when cursor is outside
        // of valid range after deleting text.
        int newCursor = getCursorPosition(connection) - range.charsBefore;
        connection.setSelection(newCursor, newCursor);
        connection.deleteSurroundingText(0, range.charsBefore + range.charsAfter);
    }

    /**
     * Represents a range of text, relative to the current cursor position.
     */
    public static class Range {
        /** Characters before selection start */
        public int charsBefore;

        /**
         * Characters after selection start, including one trailing word
         * separator.
         */
        public int charsAfter;

        /** The actual characters that make up a word */
        public String word;

        public Range() {}

        public Range(int charsBefore, int charsAfter, String word) {
            if (charsBefore < 0 || charsAfter < 0) {
                throw new IndexOutOfBoundsException();
            }
            this.charsBefore = charsBefore;
            this.charsAfter = charsAfter;
            this.word = word;
        }
    }

    private static Range getWordRangeAtCursor(
            InputConnection connection, String sep, Range range) {
        if (connection == null || sep == null) {
            return null;
        }
        CharSequence before = connection.getTextBeforeCursor(1000, 0);
        CharSequence after = connection.getTextAfterCursor(1000, 0);
        if (before == null || after == null) {
            return null;
        }

        // Find first word separator before the cursor
        int start = before.length();
        while (start > 0 && !isWhitespace(before.charAt(start - 1), sep)) start--;

        // Find last word separator after the cursor
        int end = -1;
        while (++end < after.length() && !isWhitespace(after.charAt(end), sep));

        int cursor = getCursorPosition(connection);
        if (start >= 0 && cursor + end <= after.length() + before.length()) {
            String word = before.toString().substring(start, before.length())
                    + after.toString().substring(0, end);

            Range returnRange = range != null? range : new Range();
            returnRange.charsBefore = before.length() - start;
            returnRange.charsAfter = end;
            returnRange.word = word;
            return returnRange;
        }

        return null;
    }

    private static boolean isWhitespace(int code, String whitespace) {
        return whitespace.contains(String.valueOf((char) code));
    }

    private static final Pattern spaceRegex = Pattern.compile("\\s+");

    public static CharSequence getPreviousWord(InputConnection connection,
            String sentenceSeperators) {
        //TODO: Should fix this. This could be slow!
        CharSequence prev = connection.getTextBeforeCursor(LOOKBACK_CHARACTER_NUM, 0);
        if (prev == null) {
            return null;
        }
        String[] w = spaceRegex.split(prev);
        if (w.length >= 2 && w[w.length-2].length() > 0) {
            char lastChar = w[w.length-2].charAt(w[w.length-2].length() -1);
            if (sentenceSeperators.contains(String.valueOf(lastChar))) {
                return null;
            }
            return w[w.length-2];
        } else {
            return null;
        }
    }

    /**
     * Checks if the cursor is touching/inside a word or the selection is for a whole
     * word and no more and no less.
     * @param range the Range object that contains the bounds of the word around the cursor
     * @param start the start of the selection
     * @param end the end of the selection, which could be the same as the start, if text is not
     * in selection mode
     * @return false if the selection is a partial word or straddling multiple words, true if
     * the selection is a full word or there is no selection.
     */
    public static boolean isFullWordOrInside(Range range, int start, int end) {
        // Is the cursor inside or touching a word?
        if (start == end) return true;

        // Is it a selection? Then is the start of the selection the start of the word and
        // the size of the selection the size of the word? Then return true
        if (start < end
                && (range.charsBefore == 0 && range.charsAfter == end - start)) {
            return true;
        }
        return false;
    }
}
