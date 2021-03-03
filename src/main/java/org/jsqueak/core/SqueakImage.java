/*
SqueakImage.java
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

package org.jsqueak.core;

import org.jsqueak.uilts.SqueakLogger;
import org.jsqueak.uilts.ObjectUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Hashtable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @author Daniel Ingalls
 * <p>
 * A SqueakImage represents the complete state of a running Squeak.
 * This implemenatation uses Java objects (see SqueakObject) for all Squeak objects,
 * with direct pointers between them.  Enumeration is supported by objectTable,
 * which points weakly to all objects.  SmallIntegers are modelled by Java Integers.
 * <p>
 * Some care is taken in reclaiming OT slots, to preserve the order of creation of objects,
 * as this matters for Squeak weak objects, should we ever support them.
 */

public class SqueakImage {
    private final String DEFAULT_IMAGE_NAME = "jsqueak.image";

    private SqueakVM vm;
    //private WeakReference[] objectTable;
    //private int otMaxUsed;
    //private int otMaxOld;
    //private int lastHash;
    //private int lastOTindex;

    private File imageFile;

    // FIXME: Access this through a method
    SqueakObject specialObjectsArray;

    public SqueakImage(InputStream raw) throws IOException {
        imageFile = new File(System.getProperty("user.dir"),
                DEFAULT_IMAGE_NAME);
        loaded(raw);
    }

    public SqueakImage(File fn) throws IOException {
        imageFile = fn;
        loaded(fn);
    }

    public void save(File fn) throws IOException {
        BufferedOutputStream fp = new BufferedOutputStream(new FileOutputStream(fn));
        GZIPOutputStream gz = new GZIPOutputStream(fp);
        DataOutputStream ser = new DataOutputStream(gz);
        writeImage(ser);
        ser.flush();
        ser.close();
        imageFile = fn;
    }

    File imageFile() {
        return imageFile;
    }

    void bindVM(SqueakVM theVM) {
        vm = theVM;
    }

    private void loaded(InputStream raw) throws IOException {
        BufferedInputStream fp = new BufferedInputStream(raw);
        GZIPInputStream gz = new GZIPInputStream(fp);
        DataInputStream ser = new DataInputStream(gz);
        readImage(ser);
    }

    private void loaded(File fn) throws IOException {
        FileInputStream unbuffered = new FileInputStream(fn);
        loaded(unbuffered);
        unbuffered.close();
    }

    public short registerObject(SqueakObject obj) {
        return SqueakVM.objectMemory.registerObject(obj);
    }

    private void writeImage(DataOutput ser) throws IOException {
        // Later...
        throw new IOException("Image saving is not implemented yet");
    }

    private void readImage(DataInput in) throws IOException {
        //System.err.println("-3.0" + Double.doubleToLongBits(-3.0d));
        System.out.println("Start reading at " + System.currentTimeMillis());
        Hashtable oopMap = new Hashtable(30000);
        boolean doSwap = false;
        int version = intFromInputSwapped(in, doSwap);
        if (version != 6502) {
            version = swapInt(version);
            if (version != 6502)
                throw new IOException("bad image version");
            doSwap = true;
        }
        System.err.println("version passes with swap= " + doSwap);
        int headerSize = intFromInputSwapped(in, doSwap);
        int endOfMemory = intFromInputSwapped(in, doSwap); //first unused location in heap
        int oldBaseAddr = intFromInputSwapped(in, doSwap); //object memory base address of image
        int specialObjectsOopInt = intFromInputSwapped(in, doSwap); //oop of array of special oops
        SqueakVM.objectMemory.setLastHash(intFromInputSwapped(in, doSwap)); //Should be loaded from, and saved to the image header
        int savedWindowSize = intFromInputSwapped(in, doSwap);
        int fullScreenFlag = intFromInputSwapped(in, doSwap);
        int extraVMMemory = intFromInputSwapped(in, doSwap);
        in.skipBytes(headerSize - (9 * 4)); //skip to end of header

        for (int i = 0; i < endOfMemory; ) {
            int nWords = 0;
            int classInt = 0;
            int[] data;
            int format = 0;
            int hash = 0;
            int header = intFromInputSwapped(in, doSwap);
            switch (header & Squeak.HeaderTypeMask) {
                case Squeak.HeaderTypeSizeAndClass:
                    nWords = header >> 2;
                    classInt = intFromInputSwapped(in, doSwap) - Squeak.HeaderTypeSizeAndClass;
                    header = intFromInputSwapped(in, doSwap);
                    i = i + 12;
                    break;
                case Squeak.HeaderTypeClass:
                    classInt = header - Squeak.HeaderTypeClass;
                    header = intFromInputSwapped(in, doSwap);
                    i = i + 8;
                    nWords = (header >> 2) & 63;
                    break;
                case Squeak.HeaderTypeFree:
                    throw new IOException("Unexpected free block");
                case Squeak.HeaderTypeShort:
                    i = i + 4;
                    classInt = (header >> 12) & 31; //compact class index
                    //Note classInt<32 implies compact class index
                    nWords = (header >> 2) & 63;
                    break;
            }
            int baseAddr = i - 4; //0-rel byte oop of this object (base header)
            nWords--;  //length includes base header which we have already read
            format = ((header >> 8) & 15);
            hash = ((header >> 17) & 4095);

            // Note classInt and data are just raw data; no base addr adjustment and no Int conversion
            data = new int[nWords];
            for (int j = 0; j < nWords; j++)
                data[j] = intFromInputSwapped(in, doSwap);
            i = i + (nWords * 4);

            SqueakObject javaObject = new SqueakObject(new Integer(classInt), (short) format, (short) hash, data);
            SqueakVM.objectMemory.registerObject(javaObject);
            //oopMap is from old oops to new objects
            //Why can't we use ints as keys??...
            oopMap.put(new Integer(baseAddr + oldBaseAddr), javaObject);
        }

        //Temp version of spl objs needed for makeCCArray; not a good object yet
        specialObjectsArray = (SqueakObject) (oopMap.get(new Integer(specialObjectsOopInt)));
        Integer[] ccArray = makeCCArray(oopMap, specialObjectsArray);
        int oldOop = specialObjectsArray.oldOopAt(Squeak.splOb_ClassFloat);
        SqueakObject floatClass = ((SqueakObject) oopMap.get(new Integer(oldOop)));

        System.out.println("Start installs at " + System.currentTimeMillis());
        SqueakVM.objectMemory.installObjects(oopMap, ccArray, floatClass);
        System.out.println("Done installing at " + System.currentTimeMillis());

        dumpObjOfImage();
        //Proper version of spl objs -- it's a good object
        specialObjectsArray = (SqueakObject) (oopMap.get(new Integer(specialObjectsOopInt)));

    }

    private int intFromInputSwapped(DataInput in, boolean doSwap) throws IOException {
        // Return an int from stream 'in', swizzled if doSwap is true
        int tmp = in.readInt();
        if (doSwap)
            return swapInt(tmp);
        else
            return tmp;
    }

    private int swapInt(int toSwap) {
        // Return an int with byte order reversed
        int incoming = toSwap;
        int outgoing = 0;
        for (int i = 0; i < 4; i++) {
            int lowByte = incoming & 255;
            // FIXME use bit op instead of add lowByte
            outgoing = (outgoing << 8) | lowByte;
            incoming = incoming >> 8;
        }
        return outgoing;
    }

    private Integer[] makeCCArray(Hashtable oopMap, SqueakObject splObs) {
        //Makes an aray of the complact classes as oldOops (still need to be mapped)
        int oldOop = splObs.oldOopAt(Squeak.splOb_CompactClasses);
        SqueakObject compactClassesArray = ((SqueakObject) oopMap.get(new Integer(oldOop)));
        Integer[] ccArray = new Integer[31];
        for (int i = 0; i < 31; i++) {
            ccArray[i] = new Integer(compactClassesArray.oldOopAt(i));
        }
        return ccArray;
    }

    /**
     * Dump ALL Objects from image file for debugging purpose
     */
    private void dumpObjOfImage() {
        int length = SqueakVM.objectMemory.getObjectTableLength();
        for (int i = 0; i < length; i++) {
            WeakReference<Object> objectWeakReference = SqueakVM.objectMemory.getObjectAt(i);
            if (objectWeakReference != null && objectWeakReference.get() != null) {

                Object real = objectWeakReference.get();

                if ("a Point".equals(real.toString())) {
                    // dump a Point
                    SqueakObject point = (SqueakObject) real;
                    SqueakLogger.log_D("[" + i + "] create Point: " + ObjectUtils.toString(point));

                } else if ("a Rectangle".equals(real.toString())) {
                    // dump a Rectangle
                    SqueakObject rectangle = (SqueakObject) real;
                    SqueakLogger.log_D("[" + i + "] create Rectangle: " + ObjectUtils.toString(rectangle));

                } else if ("a Float".equals(real.toString())) {
                    // dump a Float
                    SqueakObject aFloat = (SqueakObject) real;
                    SqueakLogger.log_D("[" + i + "] create Float: " + ObjectUtils.toString(aFloat));

                } else {
                    // we don't care about other object
                    SqueakLogger.log_D("[" + i + "] Create Object: " + ObjectUtils.toString(real));
                }
            }
        }
    }
}
