#!/usr/bin/env bash
# Publication automatique du dépôt FR Unifié sur GitHub.
#
# Usage :
#   GITHUB_USER=tonpseudo GITHUB_TOKEN=ghp_xxx ./publish.sh [nom-du-depot]
#
# Le token doit avoir le scope "repo" (classic) ou les permissions
# Contents: read/write + Administration: read/write (fine-grained).
set -euo pipefail

REPO_NAME="${1:-cloudstream-fr-unified}"
USER="${GITHUB_USER:?Définis GITHUB_USER}"
TOKEN="${GITHUB_TOKEN:?Définis GITHUB_TOKEN}"
SLUG="$USER/$REPO_NAME"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "==> 1/5 Remplacement du placeholder par $SLUG"
grep -rl "j97970293-lang/cloudstream-fr-unified" --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle . \
  | xargs -r sed -i "s|j97970293-lang/cloudstream-fr-unified|$SLUG|g"

echo "==> 2/5 Création du dépôt distant (ignoré s'il existe déjà)"
curl -s -o /dev/null -w "    HTTP %{http_code}\n" \
  -X POST https://api.github.com/user/repos \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"$REPO_NAME\",\"description\":\"FR Unifié — un seul catalogue (TMDB/AniList/MAL) qui fait fonctionner toutes les extensions CloudStream françaises\",\"private\":false,\"has_issues\":true}"

echo "==> 3/5 Commit des sources"
git init -q -b master 2>/dev/null || true
git add -A
git -c user.email="$USER@users.noreply.github.com" -c user.name="$USER" \
  commit -qm "FR Unifié : catalogue unique agrégeant les extensions FR" || echo "    (rien à committer)"

echo "==> 4/5 Push de la branche master"
git remote remove origin 2>/dev/null || true
git remote add origin "https://$USER:$TOKEN@github.com/$SLUG.git"
git push -q -u origin master --force

echo "==> 5/5 Push de la branche builds (plugin déjà compilé)"
TMP="$(mktemp -d)"
cp prebuilt/FrUnified.cs3 "$TMP/"
sed "s|j97970293-lang/cloudstream-fr-unified|$SLUG|g" prebuilt/plugins.json > "$TMP/plugins.json"
cd "$TMP"
git init -q -b builds
git add -A
git -c user.email="$USER@users.noreply.github.com" -c user.name="$USER" \
  commit -qm "Build initial"
git push -q --force "https://$USER:$TOKEN@github.com/$SLUG.git" builds
cd "$ROOT"
rm -rf "$TMP"

echo
echo "✅ Publié : https://github.com/$SLUG"
echo "   URL du dépôt à coller dans CloudStream :"
echo "   https://raw.githubusercontent.com/$SLUG/master/repo.json"
