package org.jsqueak.uilts;

import org.jsqueak.core.SqueakObject;

public class ObjectUtils {

    /**
     * Helper method for converting a object to string
     */
    public static String toString(Object target) {
        String res = "";

        if (target == null) {
            return "<Null Object>";
        }

        if (target instanceof Integer) {
            return "" + target.toString();
        }

        if ("a Float".equals(target.toString())) {
            SqueakObject aFloat = (SqueakObject) target;
            if (aFloat.getBits() != null) {
                return aFloat.getBits().toString();
            } else {
                return "<Error Float Number>";
            }
        }

        if ("a Point".equals(target.toString())) {
            SqueakObject point = (SqueakObject) target;

            Object[] pointers = point.getPointers();
            if (pointers != null && pointers.length == 2) {
                Object a = pointers[0];
                Object b = pointers[1];

                return ObjectUtils.toString(a) + "@" + ObjectUtils.toString(b);
            } else {
                return "<Error Point>";
            }
        }

        if ("a Rectangle".equals(target.toString())) {
            // dump a Rectangle
            SqueakObject rectangle = (SqueakObject) target;

            Object[] pointers = rectangle.getPointers();
            if (pointers != null && pointers.length == 2) {
                Object a = pointers[0];
                Object b = pointers[1];

                return ObjectUtils.toString(a) + "=>" + ObjectUtils.toString(b);
            } else {
                return "<Error Rectangle>";
            }
        }

        res = target.toString();
        return res;
    }
}
