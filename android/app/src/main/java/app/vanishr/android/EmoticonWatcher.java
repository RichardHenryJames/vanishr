package app.vanishr.android;

import android.text.Editable;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextWatcher;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Turns a face typed in a message into its emoji (":)" into a smile) when the space or the new line after it is typed, and when the
 * message is sent. Pressing delete once straight after the change brings the face back as it was typed, and it is left alone from then
 * on, so that a face can still be sent as text.
 */
final class EmoticonWatcher implements TextWatcher {
    /** Put on a face that was brought back, so that it is not changed again. */
    private static final class Kept { }

    // The latest change while nothing else has been edited: where the emoji starts, and the space after it that was typed.
    private record Change(int start, String emoji, String face, int boundary) { }

    private final BooleanSupplier restoring;
    private boolean working, undoing;
    private int typed = -1;
    private Change last;

    /** {@code restoring} says that text is being put back (a saved draft), which is not typing and is left as it is. */
    EmoticonWatcher(BooleanSupplier restoring) { this.restoring = restoring; }

    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

    @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
        if (working) return;
        boolean quiet = restoring.getAsBoolean();
        typed = !quiet && count > 0 && Emoticons.white(text.charAt(start + count - 1)) ? start + count - 1 : -1;
        undoing = last != null && before == 1 && count == 0 && start == last.boundary();
        if (!undoing) last = null;
    }

    @Override public void afterTextChanged(Editable text) {
        if (working) return;
        working = true;
        try {
            if (undoing) bringBack(text);
            else if (typed >= 0 && typed < text.length()) change(text, typed);
        } finally { working = false; undoing = false; typed = -1; }
    }

    /** Changes every face in the text that has not been brought back; for when the message is sent. */
    void convertAll(Editable text) {
        working = true;
        try {
            List<Emoticons.Found> faces = Emoticons.in(text);
            for (int index = faces.size() - 1; index >= 0; index--) {
                Emoticons.Found face = faces.get(index);
                if (!kept(text, face)) text.replace(face.start(), face.end(), face.emoji());
            }
            last = null;
        } finally { working = false; }
    }

    // The word that ends at the space that was typed, if it is a face.
    private void change(Editable text, int boundary) {
        int start = boundary;
        while (start > 0 && !Emoticons.white(text.charAt(start - 1))) {
            start--;
            if (boundary - start > Emoticons.REACH) return;
        }
        Emoticons.Found face = Emoticons.at(text, start, boundary);
        if (face == null || kept(text, face)) return;
        String original = text.subSequence(face.start(), face.end()).toString();
        text.replace(face.start(), face.end(), face.emoji());
        last = new Change(face.start(), face.emoji(), original, boundary + face.emoji().length() - original.length());
    }

    // Delete was pressed on the space that came with the change: the emoji goes back to the face, and the space stays deleted.
    private void bringBack(Editable text) {
        Change change = last;
        last = null;
        int end = change.start() + change.emoji().length();
        if (end > text.length() || !text.subSequence(change.start(), end).toString().equals(change.emoji())) return;
        int cursor = Selection.getSelectionStart(text);
        text.replace(change.start(), end, change.face());
        text.setSpan(new Kept(), change.start(), change.start() + change.face().length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (cursor >= end) Selection.setSelection(text, cursor - change.emoji().length() + change.face().length());
    }

    private static boolean kept(Editable text, Emoticons.Found face) {
        for (Kept mark : text.getSpans(face.start(), face.end(), Kept.class))
            if (text.getSpanStart(mark) == face.start() && text.getSpanEnd(mark) == face.end()) return true;
        return false;
    }
}
