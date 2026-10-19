package android.content;

public final class ComponentName {
    private final String packageName;
    private final String className;

    public ComponentName(Context context, Class<?> cls) {
        this(context == null ? "" : context.getClass().getName(), cls == null ? "" : cls.getName());
    }

    public ComponentName(String packageName, String className) {
        this.packageName = packageName == null ? "" : packageName;
        this.className = className == null ? "" : className;
    }

    public String flattenToString() {
        return packageName + "/" + className;
    }
}
