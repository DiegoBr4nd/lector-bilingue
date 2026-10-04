# Los métodos native se buscan por nombre desde C++: R8 no debe renombrarlos ni quitarlos.
-keepclasseswithmembernames,includedescriptorclasses class io.github.diegobr4nd.lectorbilingue.engine.firefox.SlimtNativeBridge {
    native <methods>;
}
