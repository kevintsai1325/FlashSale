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

# Keep orchestration tests at the Docker/API boundary; entrypoint subprocess tests above
# still exercise fail-closed validation before these fakes exist. Real bodies are preserved so
# the two tests below can restore them and exercise the actual Docker-context/nginx-binding
# wiring instead of a no-op.
real_require_local_docker_engine="$(declare -f require_local_docker_engine)"
real_require_verified_nginx_api="$(declare -f require_verified_nginx_api)"

require_local_docker_engine() { :; }
require_verified_nginx_api() { :; }

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

foreign_docker_host_fails_before_compose() {
  local output status
  if output="$(env DOCKER_HOST='tcp://remote.example.test:2376' bash "$ENTRYPOINT" cleanup 2>&1)"; then
    status=0
  else
    status=$?
  fi
  (( status != 0 )) && [[ "$output" == *'refusing non-empty DOCKER_HOST'* ]]
}

foreign_docker_context_fails_before_compose() {
  local output status
  if output="$(env DOCKER_CONTEXT='remote-production' bash "$ENTRYPOINT" cleanup 2>&1)"; then
    status=0
  else
    status=$?
  fi
  (( status != 0 )) && [[ "$output" == *'refusing non-empty DOCKER_CONTEXT'* ]]
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
  list_purchase_cleanup_counts() { :; }
  delete_purchase_demo_data() { :; }
  list_order_cleanup_counts() { :; }
  delete_order_demo_data() { :; }
  delete_analytics_demo_data() { :; }
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
  seed_order_inventory() { :; }
  redis_reset_exact_stock() { redis_reset_id="$1"; }

  seed_demo_data >/dev/null 2>&1 || return 1
  [[ "$redis_reset_id" == '42' ]]
}

foreign_docker_context_metadata_fails_before_compose_access() {
  local compose_called=0 output status

  eval "$real_require_local_docker_engine"
  docker() {
    case "$1 $2" in
      'context show') printf 'remote-ctx\n' ;;
      'context inspect') printf 'tcp://remote.example.test:2376\n' ;;
      *) return 1 ;;
    esac
  }
  compose() { compose_called=1; return 1; }

  if output="$(cleanup_demo_data 2>&1)"; then
    status=0
  else
    status=$?
  fi
  unset -f docker
  require_local_docker_engine() { :; }

  (( status != 0 )) \
    && [[ "$output" == *'refusing non-local Docker engine endpoint'* ]] \
    && [[ "$compose_called" == '0' ]]
}

cleanup_nginx_binding_failure_prevents_database_delete() {
  local database_delete_started=0

  eval "$real_require_verified_nginx_api"
  require_healthy_stack() { :; }
  require_stack_identity() { :; }
  compose() {
    case "$1 $2 $3" in
      'ps -q nginx') printf 'fake-nginx-container-id\n' ;;
      *) return 1 ;;
    esac
  }
  docker() {
    case "$1" in
      port) printf '127.0.0.1:9443\n' ;;
      *) return 1 ;;
    esac
  }
  psql_exec() {
    database_delete_started=1
    return 0
  }

  local output status
  if output="$(cleanup_demo_data 2>&1)"; then
    status=0
  else
    status=$?
  fi
  unset -f docker
  require_verified_nginx_api() { :; }

  (( status != 0 )) \
    && [[ "$output" == *'refusing nginx without verified host 8443 binding'* ]] \
    && [[ "$database_delete_started" == '0' ]]
}

cleanup_audit_barrier_begin_failure_prevents_database_delete() {
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
  list_purchase_cleanup_counts() { :; }
  delete_purchase_demo_data() { :; }
  list_order_cleanup_counts() { :; }
  delete_order_demo_data() { :; }
  delete_analytics_demo_data() { :; }
  redis_delete_exact_stock() { :; }
  begin_audit_cleanup_barrier() { return 1; }
  psql_exec() {
    database_delete_started=1
    return 0
  }

  if cleanup_demo_data >/dev/null 2>&1; then
    return 1
  fi
  [[ "$database_delete_started" == '0' ]]
}

cleanup_always_ends_audit_barrier_even_after_delete_failure() {
  local barrier_ended=0

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
  list_purchase_cleanup_counts() { :; }
  delete_purchase_demo_data() { :; }
  list_order_cleanup_counts() { :; }
  delete_order_demo_data() { :; }
  delete_analytics_demo_data() { :; }
  redis_delete_exact_stock() { :; }
  begin_audit_cleanup_barrier() { :; }
  psql_exec() { return 1; }
  end_audit_cleanup_barrier() { barrier_ended=1; }

  if cleanup_demo_data >/dev/null 2>&1; then
    return 1
  fi
  [[ "$barrier_ended" == '1' ]]
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
  seed_order_inventory() { :; }
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

if foreign_docker_host_fails_before_compose; then
  pass 'foreign DOCKER_HOST fails before Compose access'
else
  fail 'foreign DOCKER_HOST fails before Compose access'
fi

if foreign_docker_context_fails_before_compose; then
  pass 'foreign DOCKER_CONTEXT fails before Compose access'
else
  fail 'foreign DOCKER_CONTEXT fails before Compose access'
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

if foreign_docker_context_metadata_fails_before_compose_access; then
  pass 'foreign Docker context metadata fails before Compose access'
else
  fail 'foreign Docker context metadata fails before Compose access'
fi

if cleanup_nginx_binding_failure_prevents_database_delete; then
  pass 'nginx binding verification failure prevents database deletion'
else
  fail 'nginx binding verification failure prevents database deletion'
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

if cleanup_audit_barrier_begin_failure_prevents_database_delete; then
  pass 'audit barrier begin failure prevents database deletion'
else
  fail 'audit barrier begin failure prevents database deletion'
fi

if cleanup_always_ends_audit_barrier_even_after_delete_failure; then
  pass 'audit barrier end always runs, even after delete transaction failure'
else
  fail 'audit barrier end always runs, even after delete transaction failure'
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
