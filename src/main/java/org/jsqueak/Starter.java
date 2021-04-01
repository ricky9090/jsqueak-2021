/*
Starter.java
Copyright (c) 2008  Daniel H. H. Ingalls, Sun Microsystems, Inc.  All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package org.jsqueak;

import org.jsqueak.core.SqueakImage;
import org.jsqueak.core.SqueakVM;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class Starter {

    /**
     * Locate a startable image as a resource.
     */
    private static SqueakImage locateStartableImage() throws IOException {
        URL imageUrl = Starter.class.getResource(SqueakConfig.getImageName());
        if ("file".equals(imageUrl.getProtocol()))
            return new SqueakImage(new File(imageUrl.getPath()));

        InputStream ims = Starter.class.getResourceAsStream(SqueakConfig.getImageName());
        if (ims != null)
            return new SqueakImage(ims);

        throw new FileNotFoundException("Cannot locate resource " + SqueakConfig.getImageName());
    }

    /**
     * Locate a startable image at a specified path.
     */
    private static SqueakImage locateSavedImage(String pathname) throws IOException {
        File saved = new File(pathname);
        if (saved.exists())
            return new SqueakImage(saved);

        throw new FileNotFoundException("Cannot locate image " + pathname);
    }

    /**
     * @param args first arg may specify image file name
     */
    public static void boot(String[] args) throws IOException, NullPointerException, java.lang.ArrayIndexOutOfBoundsException {
        SqueakImage img = args.length > 0 ? locateSavedImage(args[1])
                : locateStartableImage();
        SqueakVM vm = new SqueakVM(img);
        SqueakVM.INSTANCE = vm;
        vm.run();
    }
}
