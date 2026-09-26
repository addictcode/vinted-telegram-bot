#!/usr/bin/env bash
# One-shot move from Railway to a fresh Ubuntu/Debian VPS, run from your machine.
#
#   deploy/deploy-vps.sh root@1.2.3.4            # first run: installs + migrates DB from Railway
#   deploy/deploy-vps.sh root@1.2.3.4 --update   # later: sync code and rebuild only
#
# Needs: SSH key access to the VPS, a logged-in `railway` CLI (for secrets + DB dump).
# Secrets are piped straight from Railway into the remote .env and never printed.
set -euo pipefail

HOST="${1:?usage: deploy/deploy-vps.sh user@host [--update]}"
MODE="${2:-}"
DIR=/opt/vinted-telegram-bot
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
SSH=(ssh -o StrictHostKeyChecking=accept-new "$HOST")

echo "==> Docker on $HOST"
"${SSH[@]}" "command -v docker >/dev/null || (curl -fsSL https://get.docker.com | sh); systemctl enable --now docker >/dev/null; mkdir -p $DIR"

echo "==> Syncing code"
rsync -az --delete \
  --exclude target --exclude .git --exclude .env --exclude backups --exclude logs \
  --exclude .idea --exclude .settings --exclude .classpath --exclude .project --exclude .factorypath \
  -e "ssh -o StrictHostKeyChecking=accept-new" ./ "$HOST:$DIR/"

if [[ "$MODE" == "--update" ]]; then
  echo "==> Rebuilding bot"
  "${SSH[@]}" "cd $DIR && docker compose up -d --build bot"
  exit 0
fi

# Proxy list downloaded from the provider, one per line: ip:port:user:pass, ip:port or a full URL.
if [[ -z "${VINTED_PROXIES:-}" && -f proxies.txt ]]; then
  VINTED_PROXIES="$(awk -F: 'NF==0 || /^#/ {next}
    /:\/\// {print; next}
    NF==4 {printf "http://%s:%s@%s:%s\n", $3, $4, $1, $2; next}
    NF==2 {printf "http://%s:%s\n", $1, $2}' proxies.txt | paste -sd, -)"
  echo "    using $(tr ',' '\n' <<<"$VINTED_PROXIES" | grep -c .) proxies from proxies.txt"
fi

echo "==> Writing .env from Railway variables"
if [[ -n "${VINTED_PROXIES:-}" ]] && "${SSH[@]}" "test -s $DIR/.env"; then
  # Refresh just the proxy line on later runs, keeping the other secrets.
  printf '%s\n' "VINTED_PROXIES=$VINTED_PROXIES" | "${SSH[@]}" \
    "umask 077; grep -v '^VINTED_PROXIES=' $DIR/.env > $DIR/.env.new; cat >> $DIR/.env.new; mv $DIR/.env.new $DIR/.env"
fi
if ! "${SSH[@]}" "test -s $DIR/.env"; then
  vars="$(railway variable list --service vinted-bot --kv)"
  get() { printf '%s\n' "$vars" | sed -n "s/^$1=//p" | head -1; }
  {
    echo "BOT_TOKEN=$(get BOT_TOKEN)"
    echo "BOT_USERNAME=$(get BOT_USERNAME)"
    echo "VINTED_PROXIES=${VINTED_PROXIES:-$(get VINTED_PROXIES)}"
    echo "DB_NAME=vinted"
    echo "DB_USER=vinted"
    echo "DB_PASSWORD=$(openssl rand -hex 24)"
  } | "${SSH[@]}" "umask 077; cat > $DIR/.env"
fi

echo "==> Starting Postgres"
"${SSH[@]}" "cd $DIR && docker compose up -d postgres && until docker compose exec -T postgres pg_isready -U vinted -d vinted >/dev/null 2>&1; do sleep 2; done"

has_schema="$("${SSH[@]}" "cd $DIR && docker compose exec -T postgres psql -U vinted -d vinted -tAc \"select to_regclass('public.users') is not null\"")"
if [[ "$has_schema" != "t" ]]; then
  echo "==> Fresh dump from Railway + restore"
  mkdir -p backups
  dump="backups/migrate-$(date +%Y%m%d-%H%M).sql"
  railway ssh --service Postgres -- 'pg_dump -U "$PGUSER" -d "$PGDATABASE" --no-owner --no-privileges' > "$dump"
  "${SSH[@]}" "cd $DIR && docker compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U vinted -d vinted" < "$dump"
fi

echo "==> Stopping the Railway bot (two instances would fight over Telegram getUpdates)"
railway down --service vinted-bot --yes || true

echo "==> Building and starting the bot"
"${SSH[@]}" "cd $DIR && docker compose up -d --build bot"
"${SSH[@]}" "cd $DIR && sleep 20 && docker compose logs --tail 20 bot"
echo "==> Done. Logs: ssh $HOST 'cd $DIR && docker compose logs -f bot'"
