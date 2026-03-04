#!/usr/bin/env bash

set -euo pipefail

TEST_ROOT="tests"
TMP_ROOT=".tmp_avaliacao"
CSV_REPORT="relatorio_avaliacao_automatica.csv"
MD_REPORT="relatorio_avaliacao_automatica.md"
TIME_LIMIT_SEC="${TIME_LIMIT_SEC:-2}"

usage() {
  echo "Uso: $0 [--time-limit SEGUNDOS] [--tests-dir CAMINHO]"
  echo "Exemplo: $0 --time-limit 2 --tests-dir tests"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --time-limit)
      TIME_LIMIT_SEC="$2"
      shift 2
      ;;
    --tests-dir)
      TEST_ROOT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Argumento invalido: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [ ! -d "$TEST_ROOT/problem1" ] || [ ! -d "$TEST_ROOT/problem2" ]; then
  echo "Erro: estrutura de testes nao encontrada em '$TEST_ROOT'." >&2
  exit 1
fi

rm -rf "$TMP_ROOT"
mkdir -p "$TMP_ROOT"

echo "student_dir,c_file,problem,case,status,details,expected_output,program_output" > "$CSV_REPORT"
{
  echo "# Relatorio de Avaliacao Automatica"
  echo ""
  echo "- Time limit por caso: ${TIME_LIMIT_SEC}s"
  echo "- Diretorio de testes: \`$TEST_ROOT\`"
  echo ""
} > "$MD_REPORT"

normalize_file() {
  local file="$1"
  sed 's/[[:space:]]\+$//' "$file" | awk '
    { lines[NR]=$0 }
    END {
      last=NR
      while (last>0 && lines[last]=="") last--
      for (i=1; i<=last; i++) print lines[i]
    }'
}

run_with_timeout() {
  local exe="$1"
  local in_file="$2"
  local out_file="$3"
  local timeout_sec="$4"

  python3 - "$exe" "$in_file" "$out_file" "$timeout_sec" <<'PY'
import subprocess
import sys

exe, in_file, out_file, timeout_sec = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])

with open(in_file, "rb") as fin:
    data = fin.read()

try:
    proc = subprocess.run(
        [exe],
        input=data,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout_sec,
    )
    with open(out_file, "wb") as fout:
        fout.write(proc.stdout)
    sys.exit(proc.returncode)
except subprocess.TimeoutExpired as exc:
    with open(out_file, "wb") as fout:
        fout.write(exc.stdout or b"")
    sys.exit(124)
PY
}

detect_problem() {
  local c_file_name="$1"
  local lower_name
  lower_name="$(printf '%s' "$c_file_name" | tr '[:upper:]' '[:lower:]')"

  if [[ "$lower_name" =~ problem1|coding_1|quiz1problem1|onlinequiz\.c ]]; then
    echo "problem1"
    return
  fi

  if [[ "$lower_name" =~ problem2|coding_2|quiz1problem2|onlinequiz2\.c ]]; then
    echo "problem2"
    return
  fi

  # Fallback: tenta identificar por digito no nome do arquivo.
  if [[ "$lower_name" =~ (^|[^0-9])1([^0-9]|$) ]]; then
    echo "problem1"
    return
  fi
  if [[ "$lower_name" =~ (^|[^0-9])2([^0-9]|$) ]]; then
    echo "problem2"
    return
  fi

  echo "unknown"
}

escape_csv() {
  local s="$1"
  s="${s//\"/\"\"}"
  printf '"%s"' "$s"
}

file_to_csv_text() {
  local file="$1"
  if [ ! -f "$file" ]; then
    printf '%s' ""
    return
  fi

  awk '{printf "%s\\n", $0}' "$file"
}

echo "Iniciando avaliacao automatica..."
echo "Relatorios: $CSV_REPORT e $MD_REPORT"

shopt -s nullglob

total_students=0
total_files=0
total_cases=0
total_pass=0

for student_dir_slash in */; do
  student_dir="${student_dir_slash%/}"

  case "$student_dir" in
    tests|"$TMP_ROOT"|.git)
      continue
      ;;
  esac

  [ -d "$student_dir" ] || continue

  c_files=( "$student_dir"/*.c )
  if [ ${#c_files[@]} -eq 0 ]; then
    continue
  fi

  total_students=$((total_students + 1))
  echo ""
  echo "Aluno/Pasta: $student_dir"
  {
    echo "## $student_dir"
    echo ""
  } >> "$MD_REPORT"

  for c_file in "${c_files[@]}"; do
    [ -f "$c_file" ] || continue
    total_files=$((total_files + 1))

    c_base="$(basename "$c_file")"
    problem="$(detect_problem "$c_base")"

    if [ "$problem" = "unknown" ]; then
      echo "  [IGNORADO] Nao foi possivel identificar problema em: $c_base"
      {
        echo "- \`$c_base\`: ignorado (problema nao identificado)"
      } >> "$MD_REPORT"
      printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$(escape_csv "$student_dir")" \
        "$(escape_csv "$c_base")" \
        "$(escape_csv "unknown")" \
        "$(escape_csv "-")" \
        "$(escape_csv "SKIP")" \
        "$(escape_csv "problema nao identificado")" \
        "$(escape_csv "-")" \
        "$(escape_csv "-")" >> "$CSV_REPORT"
      continue
    fi

    exe_path="$TMP_ROOT/${student_dir//\//_}__${c_base//[^[:alnum:]_.-]/_}.out"
    if ! gcc "$c_file" -O2 -std=c11 -o "$exe_path" 2>/dev/null; then
      echo "  [COMPILACAO] Falhou: $c_base ($problem)"
      {
        echo "- \`$c_base\` ($problem): compilacao falhou"
      } >> "$MD_REPORT"
      printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$(escape_csv "$student_dir")" \
        "$(escape_csv "$c_base")" \
        "$(escape_csv "$problem")" \
        "$(escape_csv "-")" \
        "$(escape_csv "COMPILE_ERROR")" \
        "$(escape_csv "gcc falhou")" \
        "$(escape_csv "-")" \
        "$(escape_csv "-")" >> "$CSV_REPORT"
      continue
    fi

    file_total=0
    file_pass=0

    for in_file in "$TEST_ROOT/$problem"/case*.in; do
      [ -e "$in_file" ] || continue

      case_name="$(basename "$in_file" .in)"
      expected_file="$TEST_ROOT/$problem/${case_name}.out"
      if [ ! -f "$expected_file" ]; then
        continue
      fi
      expected_text="$(file_to_csv_text "$expected_file")"

      file_total=$((file_total + 1))
      total_cases=$((total_cases + 1))

      prog_out="$TMP_ROOT/prog_${RANDOM}_${RANDOM}.txt"
      prog_norm="$TMP_ROOT/prog_norm_${RANDOM}_${RANDOM}.txt"
      exp_norm="$TMP_ROOT/exp_norm_${RANDOM}_${RANDOM}.txt"

      if run_with_timeout "$exe_path" "$in_file" "$prog_out" "$TIME_LIMIT_SEC"; then
        run_rc=0
      else
        run_rc=$?
      fi

      if [ "$run_rc" -eq 124 ]; then
        program_text="$(file_to_csv_text "$prog_out")"
        echo "    [$problem/$case_name] TIMEOUT"
        printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
          "$(escape_csv "$student_dir")" \
          "$(escape_csv "$c_base")" \
          "$(escape_csv "$problem")" \
          "$(escape_csv "$case_name")" \
          "$(escape_csv "TIMEOUT")" \
          "$(escape_csv "tempo limite excedido")" \
          "$(escape_csv "$expected_text")" \
          "$(escape_csv "$program_text")" >> "$CSV_REPORT"
        continue
      fi

      if [ "$run_rc" -ne 0 ]; then
        program_text="$(file_to_csv_text "$prog_out")"
        echo "    [$problem/$case_name] RUNTIME_ERROR"
        printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
          "$(escape_csv "$student_dir")" \
          "$(escape_csv "$c_base")" \
          "$(escape_csv "$problem")" \
          "$(escape_csv "$case_name")" \
          "$(escape_csv "RUNTIME_ERROR")" \
          "$(escape_csv "codigo de saida $run_rc")" \
          "$(escape_csv "$expected_text")" \
          "$(escape_csv "$program_text")" >> "$CSV_REPORT"
        continue
      fi

      program_text="$(file_to_csv_text "$prog_out")"

      normalize_file "$prog_out" > "$prog_norm"
      normalize_file "$expected_file" > "$exp_norm"

      if diff -q "$prog_norm" "$exp_norm" >/dev/null; then
        file_pass=$((file_pass + 1))
        total_pass=$((total_pass + 1))
        echo "    [$problem/$case_name] OK"
        printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
          "$(escape_csv "$student_dir")" \
          "$(escape_csv "$c_base")" \
          "$(escape_csv "$problem")" \
          "$(escape_csv "$case_name")" \
          "$(escape_csv "OK")" \
          "$(escape_csv "-")" \
          "$(escape_csv "$expected_text")" \
          "$(escape_csv "$program_text")" >> "$CSV_REPORT"
      else
        echo "    [$problem/$case_name] WA"
        printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
          "$(escape_csv "$student_dir")" \
          "$(escape_csv "$c_base")" \
          "$(escape_csv "$problem")" \
          "$(escape_csv "$case_name")" \
          "$(escape_csv "WA")" \
          "$(escape_csv "saida diferente do esperado")" \
          "$(escape_csv "$expected_text")" \
          "$(escape_csv "$program_text")" >> "$CSV_REPORT"
      fi
    done

    echo "  Resultado $c_base ($problem): $file_pass/$file_total"
    {
      echo "- \`$c_base\` ($problem): **$file_pass/$file_total**"
    } >> "$MD_REPORT"
  done
done

{
  echo ""
  echo "---"
  echo "- Pastas avaliadas: $total_students"
  echo "- Arquivos .c avaliados: $total_files"
  echo "- Casos executados: $total_cases"
  echo "- Casos aprovados: $total_pass"
} >> "$MD_REPORT"

echo ""
echo "Avaliacao concluida."
echo "Resumo geral: $total_pass/$total_cases casos aprovados."
echo "CSV: $CSV_REPORT"
echo "Markdown: $MD_REPORT"
