# shellcheck shell=bash
#
# Minimal Cloudflare DNS helpers, sourced by gke-deploy.sh / gke-teardown.sh.
# Needs CLOUDFLARE_API_TOKEN: an API token scoped to Zone -> DNS -> Edit on the one zone only
# (Cloudflare dashboard -> My Profile -> API Tokens -> "Edit zone DNS" template).
#
#   cf_upsert <zone> <type> <name> <content>   create or update one record, always DNS-only
#   cf_delete <zone> <type> <name>             delete it if present
#
# JSON is parsed with python3 (ships with macOS and every CI image) to avoid a jq dependency.

CF_API="https://api.cloudflare.com/client/v4"

_cf() { # _cf <method> <path> [json body]
  curl -sS --max-time 20 -X "$1" "${CF_API}$2" \
    -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN:?CLOUDFLARE_API_TOKEN is not set}" \
    -H "Content-Type: application/json" ${3:+--data "$3"}
}

_cf_json() { # _cf_json <python expression over `d`>  (reads the response on stdin)
  python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('success'), d.get('errors'); print($1)"
}

_cf_zone_id() {
  _cf GET "/zones?name=$1" | _cf_json "d['result'][0]['id'] if d['result'] else ''"
}

_cf_record_id() { # _cf_record_id <zone id> <type> <name>
  _cf GET "/zones/$1/dns_records?type=$2&name=$3" | _cf_json "d['result'][0]['id'] if d['result'] else ''"
}

cf_upsert() {
  local zone_id record_id body
  zone_id=$(_cf_zone_id "$1")
  [[ -n "$zone_id" ]] || { echo "ERROR: Cloudflare zone '$1' not found for this token" >&2; return 1; }
  record_id=$(_cf_record_id "$zone_id" "$2" "$3")
  body=$(printf '{"type":"%s","name":"%s","content":"%s","ttl":300,"proxied":false}' "$2" "$3" "$4")
  if [[ -n "$record_id" ]]; then
    _cf PUT "/zones/$zone_id/dns_records/$record_id" "$body" | _cf_json "'    updated ' + d['result']['name']"
  else
    _cf POST "/zones/$zone_id/dns_records" "$body" | _cf_json "'    created ' + d['result']['name']"
  fi
}

cf_delete() {
  local zone_id record_id
  zone_id=$(_cf_zone_id "$1")
  [[ -n "$zone_id" ]] || { echo "ERROR: Cloudflare zone '$1' not found for this token" >&2; return 1; }
  record_id=$(_cf_record_id "$zone_id" "$2" "$3")
  if [[ -n "$record_id" ]]; then
    _cf DELETE "/zones/$zone_id/dns_records/$record_id" | _cf_json "'    deleted $2 $3'"
  else
    echo "    $2 $3: not present"
  fi
}
