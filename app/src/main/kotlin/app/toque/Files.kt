package app.toque

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Remplace le fichier d'un coup : écrit à côté, force l'écriture sur le disque, puis renomme.
 * Le robot se coupe à l'interrupteur : après une coupure, on retrouve l'ancien contenu ou le
 * nouveau, jamais un fichier tronqué.
 */
fun File.replaceAtomically(write: (OutputStream) -> Unit) {
    val tmp = File("$path.part")
    try {
        FileOutputStream(tmp).use { file ->
            val out = BufferedOutputStream(file, 64 * 1024)
            write(out)
            out.flush()
            file.fd.sync()
        }
        if (!tmp.renameTo(this)) throw IOException("renommage impossible : $name")
    } finally {
        tmp.delete()
    }
}
