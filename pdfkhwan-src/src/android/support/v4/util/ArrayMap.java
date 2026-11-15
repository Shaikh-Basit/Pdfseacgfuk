package android.support.v4.util;

/**
 * Minimal stand-in for the old Android support library's ArrayMap.
 *
 * Why this class exists: PdfiumAndroid's PdfDocument keeps its native
 * page cache in an android.support.v4.util.ArrayMap, but this project
 * does not bundle the (long-dead) support library, so on a real device
 * opening any PDF crashed with
 *   NoClassDefFoundError: Landroid/support/v4/util/ArrayMap;
 *
 * The reader only ever uses it as a plain Map (put/get/size/remove),
 * so extending java.util.HashMap satisfies every call site and keeps
 * the shim free of any platform dependency (works on every API level).
 */
public class ArrayMap<K, V> extends java.util.HashMap<K, V> {
}
