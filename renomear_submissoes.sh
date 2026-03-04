#!/usr/bin/env bash

set -euo pipefail

DRY_RUN=false
METADATA_FILE="submission_metadata.yml"

usage() {
  echo "Uso: $0 [--dry-run] [arquivo_metadata.yml]"
  echo "Exemplo: $0 --dry-run submission_metadata.yml"
}

sanitize_name() {
  local raw="$1"
  local cleaned

  cleaned="$(printf '%s' "$raw" \
    | sed -E 's/[[:space:]]+/_/g; s/[^[:alnum:]_.-]//g; s/_+/_/g; s/^_+//; s/_+$//')"

  if [ -z "$cleaned" ]; then
    cleaned="aluno_sem_nome"
  fi

  printf '%s' "$cleaned"
}

pick_available_dirname() {
  local base="$1"
  local original="$2"
  local candidate="$base"
  local n=2

  while [ -e "$candidate" ] && [ "$candidate" != "$original" ]; do
    candidate="${base}_${n}"
    n=$((n + 1))
  done

  printf '%s' "$candidate"
}

rename_submission_dir() {
  local submission_id="$1"
  local student_name="$2"

  if [ ! -d "$submission_id" ]; then
    return 0
  fi

  local base_name target_name
  base_name="$(sanitize_name "$student_name")"
  target_name="$(pick_available_dirname "${base_name}__${submission_id}" "$submission_id")"

  if [ "$submission_id" = "$target_name" ]; then
    echo "Sem alteracao: $submission_id"
    return 0
  fi

  if $DRY_RUN; then
    echo "[DRY-RUN] $submission_id -> $target_name"
  else
    mv "$submission_id" "$target_name"
    echo "Renomeada: $submission_id -> $target_name"
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      METADATA_FILE="$1"
      shift
      ;;
  esac
done

if [ ! -f "$METADATA_FILE" ]; then
  echo "Erro: arquivo de metadata nao encontrado: $METADATA_FILE" >&2
  exit 1
fi

current_submission=""
name_used_for_submission=false

while IFS= read -r line; do
  if [[ "$line" =~ ^(submission_[0-9]+):$ ]]; then
    current_submission="${BASH_REMATCH[1]}"
    name_used_for_submission=false
    continue
  fi

  if [ -n "$current_submission" ] && [ "$name_used_for_submission" = false ] && [[ "$line" =~ :name:\ (.*)$ ]]; then
    student_name="${BASH_REMATCH[1]}"
    rename_submission_dir "$current_submission" "$student_name"
    name_used_for_submission=true
  fi
done < "$METADATA_FILE"

echo "Concluido."
