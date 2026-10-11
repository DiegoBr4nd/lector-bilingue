# Reglas de R8 propias de la app.
# En la fase 1b se agregan las reglas para mantener los métodos JNI de :engine:opus.

# Quitar Log.v y Log.d en release: nunca registrar texto de libros (regla 5 de CLAUDE.md).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
# (Tambien hay una prueba JVM, NoLoggingGuardTest, que prohibe Log/println en el codigo de produccion.)
