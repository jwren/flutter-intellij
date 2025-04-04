/*
 * Copyright 2019 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.editor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

class TextRangeTracker {
  private final TextRange rawRange;
  private RangeMarker marker;
  private String endingWord;

  TextRangeTracker(int offset, int endOffset) {
    rawRange = new TextRange(offset, endOffset);
  }

  void track(Document document) {
    if (marker != null) {
      assert (document == marker.getDocument());
      return;
    }
    // Create a range marker that goes from the start of the indent for the line
    // to the column of the actual entity.
    final int docLength = document.getTextLength();
    final int startOffset = Math.min(rawRange.getStartOffset(), docLength);
    final int endOffset = Math.min(rawRange.getEndOffset(), docLength);

    endingWord = getCurrentWord(document, endOffset - 1);
    marker = document.createRangeMarker(startOffset, endOffset);
  }

  @Nullable
  TextRange getRange() {
    if (marker == null) {
      return rawRange;
    }
    if (!marker.isValid()) {
      return null;
    }
    return new TextRange(marker.getStartOffset(), marker.getEndOffset());
  }

  void dispose() {
    if (marker != null) {
      marker.dispose();
    }
    marker = null;
  }

  public boolean isTracking() {
    return marker != null && marker.isValid();
  }

  /**
   * Get the next word in the document starting at offset.
   * <p>
   * This helper is used to avoid displaying outline guides where it appears
   * that the word at the start of the outline (e.g. the Widget constructor
   * name) has changed since the guide was created. This catches edge cases
   * where RangeMarkers go off the rails and return strange values after
   * running a code formatter or other tool that generates widespread edits.
   */
  public static String getCurrentWord(Document document, int offset) {
    final int documentLength = document.getTextLength();
    offset = Math.max(0, offset);
    if (offset < 0 || offset >= documentLength) return "";
    final CharSequence chars = document.getCharsSequence();
    // Clamp the max current word length at 20 to avoid slow behavior if the
    // next "word" in the document happened to be incredibly long.
    final int maxWordEnd = Math.min(documentLength, offset + 20);

    int end = offset;
    while (end < maxWordEnd && Character.isAlphabetic(chars.charAt(end))) {
      end++;
    }
    if (offset == end) return "";
    return chars.subSequence(offset, end).toString();
  }

  public boolean isConsistentEndingWord() {
    if (marker == null) {
      return true;
    }
    if (!marker.isValid()) {
      return false;
    }
    return // Verify that the word starting at the end of the marker matches
      // its expected value. This is sometimes not the case if the logic
      // to update marker locations has hit a bad edge case as sometimes
      // happens when there is a large document edit due to running a
      // code formatter.
      Objects.equals(endingWord, TextRangeTracker.getCurrentWord(marker.getDocument(), marker.getEndOffset() - 1));
  }
}

/**
 * Class that tracks the location of a FlutterOutline node in a document.
 * <p>
 * Once the track method has been called, edits to the document are reflected
 * by by all locations returned by the outline location.
 */
public class OutlineLocation implements Comparable<OutlineLocation> {
  private final int line;
  private final int column;
  private final int indent;

  @Nullable
  private String nodeStartingWord;
  private Document document;

  public OutlineLocation(
    FlutterOutline node,
    int line,
    int column,
    int indent,
    VirtualFile file
  ) {
    this.line = line;
    this.column = column;
    // These asserts catch cases where the outline is based on inconsistent
    // state with the document.
    // TODO(jacobr): tweak values so if these errors occur they will not
    // cause exceptions to be thrown in release mode.
    assert (indent >= 0);
    assert (column >= 0);
    // It makes no sense for the indent of the line to be greater than the
    // indent of the actual widget.
    assert (column >= indent);
    assert (line >= 0);
    this.indent = indent;
  }

  public void dispose() {
  }

  /**
   * This method must be called if the location is set to update to reflect
   * edits to the document.
   * <p>
   * This method must be called at most once and if it is called, dispose must
   * also be called to ensure the range marker is disposed.
   */
  public void track(Document document) {
    this.document = document;
    assert (indent <= column);
  }

  @Override
  public int hashCode() {
    int hashCode = line;
    hashCode = hashCode * 31 + column;
    hashCode = hashCode * 31 + indent;
    return hashCode;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof OutlineLocation other)) return false;
    return line == other.line &&
           column == other.column &&
           indent == other.indent;
  }

  public int getColumnForOffset(int offset) {
    assert (document != null);
    final int currentLine = document.getLineNumber(offset);
    return offset - document.getLineStartOffset(currentLine);
  }

  @Override
  public int compareTo(OutlineLocation o) {
    // We use the initial location of the outline location when performing
    // comparisons rather than the current location for efficiency
    // and stability.
    int delta = Integer.compare(line, o.line);
    if (delta != 0) return delta;
    delta = Integer.compare(column, o.column);
    if (delta != 0) return delta;
    return Integer.compare(indent, o.indent);
  }
}
