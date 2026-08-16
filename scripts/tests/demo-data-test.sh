#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENTRYPOINT="${SCRIPT_DIR}/../demo-data.sh"

# shellcheck source=../demo-data.sh
source "$ENTRYPOINT"

failures=0

fail() {
  printf 'not ok - %s\n' "$1"
  failures=$((failures + 1))
}

pass() {
  printf 'ok - %s\n' "$1"
}

invalid_url_fails_before_compose() {
  local output status
  if output="$(env DEMO_BASE_URL='https://localhost:8443/api' bash "$ENTRYPOINT" cleanup 2>&1)"; then
    status=0
  else
    status=$?
  fi
  if (( status == 0 )) || [[ "$output" != *'refusing non-local demo base URL'* ]]; then
    return 1
  fi
}

foreign_project_fails_before_compose() {
  local output status
  if output="$(COMPOSE_PROJECT_NAME='production' bash "$ENTRYPOINT" cleanup 2>&1)"; then
    status=0
  else
    status=$?
  fi
  if (( status == 0 )) || [[ "$output" != *'refusing Compose project override'* ]]; then
    return 1
  fi
}

foreign_compose_file_fails_before_compose() {
  local output status
  if output="$(env DEMO_COMPOSE_FILE='C:/Other/compose.yaml' bash "$ENTRYPOINT" cleanup 2>&1)"; then
    status=0
  else
    status=$?
  fi
  (( status != 0 )) && [[ "$output" == *'refusing foreign Compose file override'* ]]
}

foreign_env_file_fails_before_compose() {
  local output status
  if output="$(env DEMO_ENV_FILE='C:/Other/.env' bash "$ENTRYPOINT" cleanup 2>&1)"; then
    status=0
  else
    status=$?
  fi
  (( status != 0 )) && [[ "$output" == *'refusing foreign env file override'* ]]
}

delayed_registration_audit_is_swept_to_quiet() {
  local state_file delete_calls
  state_file="$(mktemp)"
  printf '1|0\n' >"$state_file"
  delete_registration_audits_exact() {
    local calls remaining
    IFS='|' read -r calls remaining <"$state_file"
    calls=$((calls + 1))
    if (( calls >= 2 )); then
      remaining=0
    else
      remaining=1
    fi
    printf '%s|%s\n' "$calls" "$remaining" >"$state_file"
  }
  count_registration_audits_exact() {
    local calls remaining
    IFS='|' read -r calls remaining <"$state_file"
    printf '%s\n' "$remaining"
  }
  demo_audit_sleep() { :; }

  if ! sweep_registration_audits_until_quiet >/dev/null 2>&1; then
    rm -f "$state_file"
    return 1
  fi
  IFS='|' read -r delete_calls _remaining <"$state_file"
  rm -f "$state_file"
  (( delete_calls >= 4 ))
}

audit_sweep_timeout_is_reported() {
  delete_registration_audits_exact() { :; }
  count_registration_audits_exact() { printf '1\n'; }
  demo_audit_sleep() { :; }
  if sweep_registration_audits_until_quiet >/dev/null 2>&1; then
    return 1
  fi
}

concurrent_registration_accepts_an_exact_winner() {
  local marker output status
  marker="$(mktemp)"
  rm -f "$marker"
  db_scalar() {
    if [[ -f "$marker" ]]; then
      printf '1\n'
    else
      printf '0\n'
    fi
  }
  curl() {
    printf 'created\n' >"$marker"
    printf '401'
  }

  if output="$(register_user_if_absent "$DEMO_USER_EMAIL" 'local-user-password' 2>&1)"; then
    status=0
  else
    status=$?
  fi
  rm -f "$marker"
  (( status == 0 )) && [[ "$output" == *'became ready concurrently'* ]]
}

failed_registration_without_an_exact_account_is_rejected() {
  db_scalar() { printf '0\n'; }
  curl() { printf '500'; }
  if register_user_if_absent "$DEMO_USER_EMAIL" 'local-user-password' >/dev/null 2>&1; then
    return 1
  fi
}

cleanup_redis_failure_prevents_database_delete() {
  local database_delete_started=0

  require_healthy_stack() { :; }
  require_stack_identity() { :; }
  db_scalar() {
    case "$1" in
      *current_database*) printf 'flashsale\n' ;;
      *string_agg*) printf '42\n' ;;
      *) return 1 ;;
    esac
  }
  list_cleanup_counts() { :; }
  redis_delete_exact_stock() { return 19; }
  psql_exec() {
    database_delete_started=1
    return 0
  }

  if cleanup_demo_data >/dev/null 2>&1; then
    return 1
  fi
  [[ "$database_delete_started" == '0' ]]
}

seed_rebuilds_the_exact_redis_stock() {
  local redis_reset_id=''
  DEMO_USER_PASSWORD='local-user-password'
  DEMO_ADMIN_PASSWORD='local-admin-password'
  export DEMO_USER_PASSWORD DEMO_ADMIN_PASSWORD

  require_healthy_stack() { :; }
  require_stack_identity() { :; }
  register_user_if_absent() { :; }
  psql_exec() { while IFS= read -r _line; do :; done; }
  db_scalar() { printf '7|42\n'; }
  redis_reset_exact_stock() { redis_reset_id="$1"; }

  seed_demo_data >/dev/null 2>&1 || return 1
  [[ "$redis_reset_id" == '42' ]]
}

seed_redis_failure_is_reported() {
  DEMO_USER_PASSWORD='local-user-password'
  DEMO_ADMIN_PASSWORD='local-admin-password'
  export DEMO_USER_PASSWORD DEMO_ADMIN_PASSWORD

  require_healthy_stack() { :; }
  require_stack_identity() { :; }
  register_user_if_absent() { :; }
  psql_exec() { while IFS= read -r _line; do :; done; }
  db_scalar() { printf '7|42\n'; }
  redis_reset_exact_stock() { return 23; }

  if seed_demo_data >/dev/null 2>&1; then
    return 1
  fi
}

if invalid_url_fails_before_compose; then
  pass 'invalid base URL fails before Compose access'
else
  fail 'invalid base URL fails before Compose access'
fi

if foreign_project_fails_before_compose; then
  pass 'foreign project override fails before Compose access'
else
  fail 'foreign project override fails before Compose access'
fi

if foreign_compose_file_fails_before_compose; then
  pass 'foreign Compose file override fails before Compose access'
else
  fail 'foreign Compose file override fails before Compose access'
fi

if foreign_env_file_fails_before_compose; then
  pass 'foreign env file override fails before Compose access'
else
  fail 'foreign env file override fails before Compose access'
fi

if delayed_registration_audit_is_swept_to_quiet; then
  pass 'delayed exact registration audit is swept through a quiet period'
else
  fail 'delayed exact registration audit is swept through a quiet period'
fi

if audit_sweep_timeout_is_reported; then
  pass 'audit quiet-period timeout is reported'
else
  fail 'audit quiet-period timeout is reported'
fi

if concurrent_registration_accepts_an_exact_winner; then
  pass 'concurrent registration accepts only the exact winning account'
else
  fail 'concurrent registration accepts only the exact winning account'
fi

if failed_registration_without_an_exact_account_is_rejected; then
  pass 'failed registration without an exact account is rejected'
else
  fail 'failed registration without an exact account is rejected'
fi

if cleanup_redis_failure_prevents_database_delete; then
  pass 'Redis cleanup failure prevents database deletion'
else
  fail 'Redis cleanup failure prevents database deletion'
fi

if seed_rebuilds_the_exact_redis_stock; then
  pass 'seed rebuilds exact Redis stock after database reset'
else
  fail 'seed rebuilds exact Redis stock after database reset'
fi

if seed_redis_failure_is_reported; then
  pass 'Redis seed failure is returned to the caller'
else
  fail 'Redis seed failure is returned to the caller'
fi

if (( failures > 0 )); then
  printf '%d orchestration test(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'all demo-data orchestration tests passed\n'
