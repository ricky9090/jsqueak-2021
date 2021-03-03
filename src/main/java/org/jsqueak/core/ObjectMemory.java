package org.jsqueak.core;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Objects;

/**
 * ObjectMemory for memory management.
 * Use ArrayList instead of Array for objectTable
 * The memory usage and GC performance are almost the same as the old implementation
 */
public class ObjectMemory {

    private final static int OTMinSize = 120000;
    private final static int OTMaxSize = 640000;  // not used
    private final static int OTGrowSize = 10000;

    private final SqueakVM vm;

    private final ArrayList<WeakReference<Object>> objectTable;

    private int currentCapacity;
    private int lastObjectIndex;

    private int lastHash;

    private int nullCount = 0;

    public ObjectMemory() {
        currentCapacity = OTMinSize;
        this.objectTable = new ArrayList<>(currentCapacity);
        vm = SqueakVM.INSTANCE;
    }

    public int getLastHash() {
        return lastHash;
    }

    public void setLastHash(int lastHash) {
        this.lastHash = lastHash;
    }

    public int getObjectTableLength() {
        return objectTable.size();
    }

    public WeakReference<Object> getObjectAt(int index) {
        if (objectTable.size() <= index) {
            return null;
        }
        return objectTable.get(index);
    }

    public boolean bulkBecome(Object[] fromPointers, Object[] toPointers, boolean twoWay) {
        int n = fromPointers.length;
        Object p, ptr, body[], mut;
        SqueakObject obj;
        if (n != toPointers.length)
            return false;
        Hashtable<Object, Object> mutations = new Hashtable<>(n * 4 * (twoWay ? 2 : 1));
        for (int i = 0; i < n; i++) {
            p = fromPointers[i];
            if (!(p instanceof SqueakObject))
                return false;  //non-objects in from array
            if (mutations.get(p) != null)
                return false; //repeated oops in from array
            else
                mutations.put(p, toPointers[i]);
        }
        if (twoWay) {
            for (int i = 0; i < n; i++) {
                p = toPointers[i];
                if (!(p instanceof SqueakObject))
                    return false;  //non-objects in to array
                if (mutations.get(p) != null)
                    return false; //repeated oops in to array
                else
                    mutations.put(p, fromPointers[i]);
            }
        }
        for (int i = 0; i <= objectTable.size(); i++) {
            // Now, for every object...
            obj = (SqueakObject) objectTable.get(i).get();
            if (obj != null) {
                // mutate the class
                mut = (SqueakObject) mutations.get(obj.sqClass);
                if (mut != null)
                    obj.sqClass = mut;
                if ((body = obj.pointers) != null) {
                    // and mutate body pointers
                    for (int j = 0; j < body.length; j++) {
                        ptr = body[j];
                        mut = mutations.get(ptr);
                        if (mut != null)
                            body[j] = mut;
                    }
                }
            }
        }
        return true;
    }

    //Enumeration...
    public SqueakObject nextInstance(int startingIndex, SqueakObject sqClass) {
        //if sqClass is null, then find next object, else find next instance of sqClass
        final int length = objectTable.size();
        for (int i = startingIndex; i <= length; i++) {
            // For every object...
            SqueakObject obj = (SqueakObject) objectTable.get(i).get();
            if (obj != null && (sqClass == null | obj.sqClass == sqClass)) {
                lastObjectIndex = i; // save hint for next scan
                return obj;
            }
        }
        return SqueakVM.nilObj;  // Return nil if none found
    }

    public int otIndexOfObject(SqueakObject lastObj) {
        // hint: lastObj should be at lastObjectIndex
        SqueakObject obj = (SqueakObject) objectTable.get(lastObjectIndex).get();
        if (obj == lastObj) {
            return lastObjectIndex;
        } else {
            final int length = objectTable.size();
            for (int i = 0; i <= length; i++) {
                // Alas no; have to find it again...
                obj = (SqueakObject) objectTable.get(i).get();
                if (obj == lastObj)
                    return i;
            }
        }
        return -1;  //should not happen
    }

    public short registerObject(SqueakObject obj) {
        //All enumerable objects must be registered
        int currentSize = objectTable.size();
        if (currentSize + 1 > currentCapacity) {  // reach capacity
            performGC();
        }

        objectTable.add(new WeakReference<>(obj));
        lastHash = 13849 + (27181 * lastHash);
        return (short) (lastHash & 0xFFF);
    }

    private void performGC() {
        //SqueakLogger.log_D("freeSpace " + System.currentTimeMillis() + ", currentObjectCount: " + objectTable.size());
        for (int i = 0; i < 5; i++) {
            if (i == 2 && vm != null) {
                vm.clearCaches(); //only flush caches after two tries
            }
            partialGC();
            if (nullCount >= OTGrowSize) {
                return; // do not need increase capacity
            }
        }

        // Sigh -- really need more space...
        fullGC();
        objectTable.ensureCapacity(currentCapacity + OTGrowSize);
        currentCapacity += OTGrowSize;
    }

    public int partialGC() {
        System.gc();
        reclaimNullOTSlots();
        return spaceLeft();
    }

    int fullGC() {
        if (SqueakVM.INSTANCE == null) {
            return spaceLeft();
        }
        SqueakVM.INSTANCE.clearCaches();
        for (int i = 0; i < 5; i++) {
            partialGC();
        }
        return spaceLeft();
    }

    public int spaceLeft() {
        return (int) Math.min(Runtime.getRuntime().freeMemory(), (long) SqueakVM.maxSmallInt);
    }

    private int reclaimNullOTSlots() {
        // Java GC will null out slots in the weak Object Table.
        // This procedure compacts the occupied slots (retaining order),
        // and returns objectTable size after shrink.
        nullCount = 0;
        int writePtr = 0;
        int end = objectTable.size() - 1;
        int orgSize = objectTable.size();
        for (int readPtr = 0; readPtr <= end; readPtr++) {
            if (objectTable.get(readPtr) != null && objectTable.get(readPtr).get() != null) {
                objectTable.set(writePtr, objectTable.get(readPtr));
                writePtr++;
            } else {
                nullCount++;
            }
        }

        // shrink objectTable size
        ArrayList<WeakReference<Object>> tmp = new ArrayList<>(objectTable);
        int size = objectTable.size();
        objectTable.clear();
        for (int i = 0; i < size; i++) {
            if (tmp.get(i) != null && tmp.get(i).get() != null) {
                objectTable.add(tmp.get(i));
            }
        }

        return objectTable.size();
    }

    public void installObjects(Hashtable<Object, Object> oopMap, Integer[] ccArray, SqueakObject floatClass) {
        for (int i = 0; i < objectTable.size(); i++) {
            // Don't need oldBaseAddr here**
            if (i == 39823) {
                // TODO change index to watch Object installing in debug mode
                int temp = 0;  // foobar statement, set break point here
            }
            try {
                SqueakObject t = (SqueakObject) Objects.requireNonNull(objectTable.get(i).get());
                t.install(oopMap, ccArray, floatClass);
            } catch (Exception ignored) {

            }
        }
    }
}
