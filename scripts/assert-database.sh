#!/usr/bin/env bash
set -euo pipefail
# Independent SQL verification after scripts/e2e.mjs on a fresh compose stack.
result=$(docker compose exec -T mysql sh -c 'mysql -u flashsale -p"$MYSQL_PASSWORD" flashsale -N -e "SELECT c.stock,(SELECT COUNT(*) FROM coupon_order WHERE coupon_id=3),(SELECT COUNT(*) FROM (SELECT user_id FROM coupon_order WHERE coupon_id=3 GROUP BY user_id HAVING COUNT(*)>1) d) FROM coupon c WHERE id=3"' 2>/dev/null)
read -r stock orders duplicates <<< "$result"
[[ "$stock" = "0" && "$orders" = "20" && "$duplicates" = "0" ]] || { echo "Database invariant failed: $result"; exit 1; }
echo "MySQL verified: stock=0, orders=20, duplicate buyers=0"
