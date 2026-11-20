# Certificats racine ajoutés

Android 6 (tablette du robot) ne connaît pas certaines racines utilisées aujourd'hui par GitHub :
sans elles, la recherche et le téléchargement des mises à jour échouent (« Connexion sécurisée à
GitHub impossible »). Toque les ajoute à celles d'Android (`app/src/main/kotlin/app/toque/Tls.kt`) ;
la vérification des certificats reste complète.

| Fichier | Racine | Utilisée par | SHA-256 |
|---|---|---|---|
| `isrg-root-x1.pem` | ISRG Root X1 (Let's Encrypt), expire en 2035 | téléchargement des APK (`release-assets.githubusercontent.com`) | `96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6` |
| `usertrust-ecc.pem` | USERTrust ECC Certification Authority (Sectigo), expire en 2038 | `api.github.com`, `github.com` | `4F:F4:60:D5:4B:9C:86:DA:BF:BC:FC:57:12:E0:40:0D:2B:ED:3F:BC:4D:4F:BD:AA:86:E0:6A:DC:D2:A9:AD:7A` |
| `usertrust-rsa.pem` | USERTrust RSA Certification Authority (Sectigo), expire en 2038 | sites en chaîne Sectigo RSA | `E7:93:C9:B0:2F:D8:AA:13:E2:1C:31:22:8A:CC:B0:81:19:64:3B:74:9C:89:89:64:B1:74:6D:46:C3:D4:CB:D2` |

Exportés du magasin de certificats racine de macOS ; empreintes conformes aux valeurs publiées par
Let's Encrypt et Sectigo. Vérifier : `openssl x509 -in <fichier> -noout -fingerprint -sha256`.
