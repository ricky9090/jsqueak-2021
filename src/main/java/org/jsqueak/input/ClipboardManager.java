package org.jsqueak.input;

import org.jsqueak.core.SqueakObject;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

/**
 * Clipboard manager between squeak world and OS
 */
public class ClipboardManager {

    String clipboard;

    public void reset() {
        clipboard = null;
    }

    public String getClipboard() {
        return clipboard;
    }

    public int clipboardSize() {
        if (clipboard == null) {
            return 0;
        }
        return clipboard.length();
    }

    public void clipboardWrite(String str) {
        this.clipboard = str;

        writeToSystemClipboard(str);
    }

    /**
     * Write the clipboard content from squeak to OS
     */
    public void clipboardWrite(SqueakObject aStringObj) {
        String asStr = aStringObj.asString();
        clipboard = asStr;

        writeToSystemClipboard(asStr);
    }

    /**
     * Read clipboard content from OS, return as a Squeak String object
     */
    public SqueakObject clipboardRead() {
        String target;
        if (clipboard == null) {
            target = readSystemClipboard();
        } else {
            // Because we write system clipboard every time the clipboardWrite() called
            // so if there is a difference between two clipboards,
            // the system one should have been updated outside from squeak
            String systemClipboardText = readSystemClipboard();
            if (clipboard.equals(systemClipboardText)) {
                target = clipboard;
            } else {
                target = systemClipboardText;
            }
        }
        return SqueakObject.createSTString(target);
    }

    private void writeToSystemClipboard(String str) {
        Clipboard systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable transText = new StringSelection(str);
        systemClipboard.setContents(transText, null);
    }

    private String readSystemClipboard() {
        String result = "";

        Clipboard systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable systemClipContent = systemClipboard.getContents(null);

        if (systemClipContent != null) {
            if (systemClipContent.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                try {
                    result = (String) systemClipContent.getTransferData(DataFlavor.stringFlavor);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return result;
    }
}
