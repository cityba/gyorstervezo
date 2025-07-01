# TervezőApp

[![Android API](https://img.shields.io/badge/API-21%2B-green)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Interaktív terv készítő Android alkalmazás gyors vázlatkészítéshez és szakmai előterjesztéshez, Floorplanner 3D generáláshoz.

---

## Tartalomjegyzék

* [Funkciók](#funkciók)
* [Telepítés](#telepítés)
* [Használat](#használat)
* [Előnyök](#előnyök)
* [Rendszerkövetelmények](#rendszerkövetelmények)
* [Hozzájárulás](#hozzájárulás)
* [Licenc](#licenc)

---

## Funkciók

* **Falrajzolás**: 10 cm és 30 cm vastagságú falak rácshoz igazítva.
* **Ablak & Ajtó**: Méretbeállítás és falra pattintás egyszerűen.
* **Szerkesztés mód**: Mozgatás, átméretezés, törlés érintéssel.
* **Zoom & Pan**: Kétujjas pinch-zoom és drag.
* **Mentés & Betöltés**: Automatikus munkamenet-mentés.
* **Export**: JPEG és Floorplanner-kompatibilis FML formátum.

---

## Telepítés

1. Klónozd a repót:

   ```bash
   git clone https://github.com/cityba/gyorstervezo.git
   cd REPO
   ```
2. Apk telepítése:

   ```bash
   adb install -r app-debug.apk
   ```

---

## Használat

1. **Válassz eszközt**: Főfal vagy válaszfal gomb.
2. **Rajzolj**: Érintéssel indíts, húzd az ujjad a végpontig.
3. **Szerkesztés**: Kapcsold be a szerkesztőmódot, érintsd meg az elemet.
4. **Exportálás**: Export gomb → JPEG vagy FML → mentés a Képek/Dokumentumok mappába.

---

## Előnyök

* **Gyors prototípus**: Percek alatt kész vázlat.
* **Szakmai export**: Képi és FML formátum.
* **Offline**: Internet nélkül is használható.
* **Egyszerű UI**: Felhasználóbarát, minimális tanulási görbe.

---

## Rendszerkövetelmények

* Android 5.0 (API 21) vagy újabb
* Engedélyezett ismeretlen források

---

## Hozzájárulás

1. Forkold a repót 🚀
2. Branch létrehozása: `git checkout -b feature/uj-feature`
3. Commit: `git commit -m "Új feature hozzáadva"`
4. Push & Pull Request

---

## Licenc

Ez a projekt **MIT License** alatt áll. Részletek a [LICENSE](LICENSE) fájlban.
