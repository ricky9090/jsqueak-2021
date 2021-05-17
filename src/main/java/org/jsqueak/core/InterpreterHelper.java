package org.jsqueak.core;

import static org.jsqueak.core.SqueakVM.*;

/**
 * Implementation refer to SqueakJS
 * @see <a href="https://github.com/codefrau/SqueakJS">codefrau/SqueakJS</a>
 *
 */
public class InterpreterHelper {

    // TODO Determine to use either a singleton or pure util class
    // Currently, some of the VM state field is accessing & modifying by SqueakVM.INSTANCE

    private InterpreterHelper() {}

    public static <T> T fetchPointerOfObject(int index, Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            /*if (target.pointers == null || target.pointers.length == 0) {
                return null;
            }
            if (target.pointers.length <= index) {
                return null;
            }*/

            Object tmp = target.pointers[index];

            return (T) tmp;
        }
        return null;
    }

    public static int fetchIntegerOfObject(int index, Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            Object tmp = target.pointers[index];
            if (tmp instanceof Integer) {
                return (int) tmp;
            }
        }
        SqueakVM.INSTANCE.setSuccess(false);
        return 0;
    }

    //region testing method

    public static boolean isBytes(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format >= 8 && target.format <= 11;
        }
        return false;
    }

    public static boolean isPointers(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format <= 4;
        }
        return false;
    }

    public static boolean isWords(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format == 6;
        }
        return false;
    }

    public static boolean isWordsOrBytes(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format == 6 || (target.format >= 8 && target.format <= 11);
        }
        return false;
    }

    public static boolean isWeak(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format == 4;
        }
        return false;
    }

    public static boolean isMethod(Object obj) {
        if (obj instanceof SqueakObject) {
            SqueakObject target = (SqueakObject) obj;
            return target.format >= 12;
        }
        return false;
    }

    public static int SIZEOF(Object obj) {
        if (isPointers(obj)) {
            return ((SqueakObject) obj).pointersSize();
        }
        if (isWordsOrBytes(obj)) {
            return ((SqueakObject) obj).bitsSize();
        }
        return 0;
    }

    public static void primitiveFail() {
        SqueakVM.INSTANCE.setSuccess(false);
    }

    public static int SHL(int a, int b) {
        return b > 31 ? 0 : a << b;
    }
    public static int SHR(int a, int b) {
        return b > 31 ? 0 : a >>> b;
    }

    public static boolean assertClassOfIs(Object target, Object classObject) {
        if (target instanceof SqueakObject) {
            return ((SqueakObject) target).sqClass == classObject;
        }
        return false;
    }

    //endregion

    //SmallIntegers are stored as Java (boxed)Integers
    public static boolean canBeSTInteger(int anInt) {
        return (anInt >= minSmallInt) && (anInt <= maxSmallInt);
    }

    public static Integer smallFromInt(int raw) {
        // canBeSTInteger
        if (raw >= minSmallInt && raw <= maxSmallInt) {
            return raw;
        }
        return null;
    }

    public static boolean isSTInteger(Object obj) {
        return obj instanceof Integer;
    }

    public static boolean isSTFloat(Object obj) {
        if (isSTInteger(obj)) {
            return false;
        }
        return ((SqueakObject) obj).getSqClass() == specialObjects[Squeak.splOb_ClassFloat];
    }

    // Java rounds toward zero, we also need towards -infinity, so...
    public static int div(int rcvr, int arg) {
        // do this without floats asap
        if (arg == 0) {
            return nonSmallInt;  // fail if divide by zero
        }
        return (int) Math.floor((double) rcvr / arg);
    }

    public static int quickDivide(int rcvr, int arg) {
        // only handles exact case
        if (arg == 0) {
            return nonSmallInt;  // fail if divide by zero
        }
        int result = rcvr / arg;
        if (result * arg == rcvr) {
            return result;
        }
        return nonSmallInt;   // fail if result is not exact
    }

    public static int mod(int rcvr, int arg) {
        if (arg == 0) {
            return nonSmallInt;  // fail if divide by zero
        }
        return rcvr - div(rcvr, arg) * arg;
    }

    public static int safeMultiply(int multiplicand, int multiplier) {
        int product = multiplier * multiplicand;
        //check for overflow by seeing if computation is reversible
        // FIXME
        if (multiplier == 0) {
            return product;
        }
        if ((product / multiplier) == multiplicand) {
            return product;
        }
        return nonSmallInt;   //non-small result will cause failure
    }

    public static int safeShift(int bitsToShift, int shiftCount) {
        if (shiftCount < 0) {
            return bitsToShift >> -shiftCount; //OK ot lose bits shifting right
        }
        //check for lost bits by seeing if computation is reversible
        int shifted = bitsToShift << shiftCount;
        if ((shifted >>> shiftCount) == bitsToShift) {
            return shifted;
        }
        return nonSmallInt;   //non-small result will cause failure
    }
}
