# Dystrybucja i auto-aktualizacje przez F-Droid (`word-apk`)

Repozytorium jest przygotowane zarówno pod **własny sklep F-Droid na GitHub Pages** (działa od razu po jednorazowym dodaniu kluczy podpisu), jak i pod zgłoszenie do **oficjalnego katalogu F-Droid (`fdroiddata`)**.

---

## Jak F-Droid wykrywa nowe wersje („podpinamy aktualizacje”)

Stosujemy **4-częściowe wersjonowanie** (`<upstream>.<nasz_kolejny_numer>`):
* Pierwsze trzy człony odpowiadają bazie z `upstream` (`Cargo.toml`, np. `0.18.1`, później `0.19.0`).
* Czwarty człon należy do wydań Androida (`0.18.1.1` → `0.18.1.2` → …; po aktualizacji bazy do `0.19.0` przechodzimy na `0.19.0.1` → `0.19.0.2`).
* **`versionCode` rośnie zawsze o +1** przy każdej nowej paczce (`1` → `2` → `3` → …), niezależnie od resetu czwartej cyfry po skoku bazy `upstream` — to po `versionCode` Android i F-Droid rozpoznają aktualizację.

Przy każdej poprawce, którą chcesz wypuścić jako aktualizację:
1. Zwiększ `versionCode` (np. `1` → `2`) oraz zmień `versionName` (np. `"0.18.1.1"` → `"0.18.1.2"`, albo po aktualizacji rdzenia → `"0.19.0.1"`) w [`android/app/build.gradle.kts`](../../android/app/build.gradle.kts).
2. Dopisz krótki opis zmian w pliku nazwanym numerem `versionCode`: `fastlane/metadata/android/pl-PL/changelogs/<versionCode>.txt` oraz `en-US/changelogs/<versionCode>.txt` (np. `2.txt`).
3. Zatwierdź zmiany na `main` i wypchnij pasujący tag wersji (`v` + dokładne `versionName`):
   ```sh
   git tag v0.18.1.2
   git push origin v0.18.1.2
   ```

Po wypchnięciu tagu `v*`:
* **Własne repozytorium F-Droid (GitHub Pages):** workflow [`.github/workflows/fdroid-repo.yml`](../../.github/workflows/fdroid-repo.yml) buduje podpisany plik `.apk`, dodaje go do GitHub Release i aktualizuje indeks F-Droid na GitHub Pages (`https://lukasz-sklad.github.io/word-apk/repo`). Aplikacja F-Droid / Droid-ify na telefonie sama widzi nowszy `versionCode` i pobiera aktualizację.
* **Oficjalny F-Droid (`fdroiddata`):** serwer F-Droida wykrywa nowy tag dzięki `UpdateCheckMode: Tags` z przepisu [`one.lmk.wordcraft.yml`](one.lmk.wordcraft.yml), buduje aplikację czysto ze źródeł i publikuje aktualizację w głównym sklepie.

---

## 1. Jednorazowa konfiguracja własnego repozytorium F-Droid (GitHub Pages)

Aby podpisy aplikacji i indeksu repozytorium nigdy nie zmieniały odcisku (inaczej klient F-Droid odrzuci aktualizację!), dodaj w **GitHub → Settings → Secrets and variables → Actions** następujące sekrety:

### Klucz aplikacji Android (APK)
* `RELEASE_KEYSTORE_BASE64` — zakodowany w Base64 plik keystore aplikacji (`.jks` / `.p12`)
* `KEYSTORE_PASSWORD`
* `KEY_ALIAS`
* `KEY_PASSWORD`

### Klucz podpisu indeksu repozytorium F-Droid (osobny keystore `.p12`)
Wygeneruj raz na komputerze (nigdy nie wgrywaj pliku klucza do gita!):
```sh
keytool -genkeypair -v -keystore fdroid-repo.p12 -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 9125 -alias fdroid-repo \
  -dname "CN=lukasz-sklad, OU=WordCraft Android, O=Community"
base64 -w 0 fdroid-repo.p12
```
Dodaj w GitHub Secrets:
* `FDROID_REPO_KEYSTORE_BASE64` — zawartość Base64 pliku `fdroid-repo.p12`
* `FDROID_REPO_KEYSTORE_PASS`
* `FDROID_REPO_KEY_PASS`
* `FDROID_REPO_KEY_ALIAS` (np. `fdroid-repo`)

Następnie w **GitHub → Settings → Pages** ustaw **Source: GitHub Actions**.

Po pierwszym wypchnięciu tagu `v*` adres Twojego repozytorium F-Droid to:
`https://lukasz-sklad.github.io/word-apk/repo`

---

## 2. Zgłoszenie do oficjalnego sklepu F-Droid (`fdroiddata`)

1. Otwórz fork projektu [gitlab.com/fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).
2. Skopiuj plik [`packaging/fdroid/one.lmk.wordcraft.yml`](one.lmk.wordcraft.yml) jako `metadata/one.lmk.wordcraft.yml` w tamtym repozytorium (zaktualizuj `versionName`, `versionCode`, `commit` na najnowszy wypchnięty tag).
3. Złóż Merge Request w `fdroid/fdroiddata`.
