# Reglas de R8 propias de la app.
# En la fase 1b se agregan las reglas para mantener los métodos JNI de :engine:opus.

# Quitar Log.v y Log.d en release: nunca registrar texto de libros (regla 5 de CLAUDE.md).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
