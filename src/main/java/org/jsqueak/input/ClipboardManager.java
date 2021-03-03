package org.jsqueak.input;

import org.jsqueak.core.SqueakObject;

public class ClipboardManager {

    String clipboard;

    public void reset() {
        clipboard = null;
    }

    public int clipboardSize() {
        if (clipboard == null) {
            return 0;
        }
        return clipboard.length();
    }

    public void clipboardWrite(String str) {
        this.clipboard = str;
    }

    public void clipboardWrite(SqueakObject aStringObj) {
        clipboard = aStringObj.asString();
    }

    public String getClipboard() {
        return clipboard;
    }

    public SqueakObject clipboardRead() {
        String target;
        if (clipboard == null) {
            target = "";
        } else {
            target = clipboard;
        }
        SqueakObject aStringObj = SqueakObject.createSTString(target);
        return aStringObj;
    }
}
