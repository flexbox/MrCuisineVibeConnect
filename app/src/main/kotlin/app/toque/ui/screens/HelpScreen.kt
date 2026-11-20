package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.toque.core.i18n.tr
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Explications des réglages : la page Réglages ne garde que les commandes et les états. */
class HelpScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Aide sur les fonctionnalités")

    /** Une explication : le nom du réglage (facultatif), puis son texte. */
    private class Item(val name: String, val text: String)

    override fun build(): View {
        val col = Ui.horizontal(c, Gravity.TOP)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        val left = Ui.vertical(c)
        val right = Ui.vertical(c)
        col.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(18) })
        col.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }

        section(left, tr("Machine"), Icon.Kind.POT,
            Item(tr("Démo"), tr("Base moteur simulée : pour essayer l'appli sur n'importe quel appareil Android.")),
            Item(tr("Jumeau (PC)"), tr("Jumeau numérique du SDK lancé sur un PC (tools/twin.py). Avec « adb reverse tcp:8765 tcp:8765 », gardez 127.0.0.1.")),
            Item(tr("Machine réelle"), tr("Pilote le vrai moteur par le port série. Exige le root. L'appli d'origine est coupée du moteur (réversible).")))
        section(left, tr("Sécurité"), Icon.Kind.WARNING,
            Item(tr("Exiger la détection du bol"), tr("Refuse de démarrer si le bol n'est pas sur la base. Avant de l'activer, vérifiez que la ligne « Capteur » des Réglages change bien quand vous retirez le bol.")),
            Item(tr("Montée en vitesse progressive à chaud"), tr("À partir de 60 °C, la lame atteint sa vitesse en 2 à 3 secondes pour éviter les projections. À froid, elle y va directement.")),
            Item(tr("Faire confirmer les réglages des recettes"), tr("Bouton « J'ai vérifié ce réglage » avant chaque étape machine d'une recette importée (Lidl ou Cookomix). Désactivé, les réglages hors limites du robot restent refusés par la sécurité.")),
            Item(tr("Enchaîner les étapes machine des recettes"), tr("Les réglages qui se suivent sans geste à faire entre eux partent ensemble d'un seul appui sur Lancer : la lame passe directement à la vitesse et à la température suivantes.")))
        section(left, tr("Écran de veille"), Icon.Kind.CLOCK,
            Item("", tr("Un toucher ou le bouton rotatif réveille l'écran. Jamais de veille pendant un programme, une alerte ou une synchro de recettes.")),
            Item(tr("Cadre photo (album Google Photos)"), tr("Dans Google Photos, partagez un album par lien, puis saisissez ici le lien ou seulement sa fin (après photos.app.goo.gl/). Toute personne qui a ce lien peut voir l'album : le robot le lit tel quel, sans compte, et n'enregistre aucune photo. Les vidéos ne sont pas affichées.")),
            Item("", tr("Pendant le diaporama, le bouton rotatif passe à la photo suivante ou précédente ; un premier toucher met l'écran en pleine lumière, un second le réveille. Sans réseau, la veille affiche l'heure.")))

        section(right, tr("Date et heure"), Icon.Kind.CLOCK,
            Item("", tr("L'heure et le fuseau horaire viennent de la connexion Internet : le robot n'a pas de pile d'horloge et repart d'une date ancienne à chaque démarrage. Tant que le réseau n'a pas répondu, l'horloge affiche « --:-- ». Le fuseau peut aussi se choisir à la main.")))
        section(right, tr("Recettes Lidl"), Icon.Kind.SYNC,
            Item("", tr("Toque télécharge lui-même les recettes officielles du Monsieur Cuisine connect sur monsieur-cuisine.com (sans compte).")),
            Item(tr("Importer de l'appli d'origine"), tr("Autre source : les recettes déjà présentes dans l'appli d'origine du robot (remplacent celles du site).")))
        section(right, tr("Recettes Cookomix"), Icon.Kind.SYNC,
            Item("", tr("Toque télécharge les recettes de cookomix.com (écrites pour le Thermomix) et garde celles que le Monsieur Cuisine connect peut faire. Les réglages sont convertis ; vérifiez-les avant de lancer chaque étape.")),
            Item("", tr("La première fois, compter une heure ou plus (une page toutes les 1 à 2 secondes, photos comprises, pour ne pas surcharger le site). Les recettes apparaissent au fur et à mesure.")),
            Item(tr("Inclure les recettes adaptées"), tr("Recettes dont un réglage a dû être modifié (vitesse en chauffe limitée à 3, pétrissage découpé en 2 min…). Elles portent le mot-clé « Adaptée MCC ».")))
        section(right, tr("Mises à jour"), Icon.Kind.INFO,
            Item("", tr("Toque cherche ses nouvelles versions sur GitHub, les installe lui-même puis redémarre. Refusé pendant un programme.")))
        section(right, tr("À propos"), Icon.Kind.INFO,
            Item("", tr("Toque %s · protocole d'après le Monsieur Cuisine Developer SDK (licence MIT). " +
                "Projet indépendant, non affilié à Lidl, SilverCrest ni Cookomix.", version)))
        return Ui.scroll(c, col)
    }

    private fun section(parent: LinearLayout, title: String, icon: Icon.Kind, vararg items: Item) {
        val card = Ui.card(c, 24f)
        val head = Ui.horizontal(c)
        head.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head, Ui.lp(b = 4))
        for (item in items) {
            if (item.name.isNotEmpty()) card.addView(Ui.text(c, item.name, 17f, Ui.TEXT, Ui.MEDIUM), Ui.lp(t = 12))
            card.addView(Ui.text(c, item.text, 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(t = if (item.name.isEmpty()) 10 else 4))
        }
        parent.addView(card, Ui.lp(b = 16))
    }
}
