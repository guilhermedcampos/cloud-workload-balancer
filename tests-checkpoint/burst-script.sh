#!/bin/bash
set -euo pipefail

HOST="${CNV_HOST:-127.0.0.1:8000}"
ROUNDS=10
PARALLEL=2

SEQ1_CONTENT=$(grep -v '^>' sars-10k.fasta | tr -d '\n\r' | head -c 800)
SEQ2_CONTENT=$(grep -v '^>' human-mc-10k.fasta | tr -d '\n\r' | head -c 800)

SEQ1="sars-10k:${SEQ1_CONTENT}"
SEQ2="human-mc-10k:${SEQ2_CONTENT}"

make_request() {
    curl -s "http://${HOST}/dna?minLength=50&stopOnFirst=true" \
        -G \
        --data-urlencode "seq1=${SEQ1}" \
        --data-urlencode "seq2=${SEQ2}" \
        -o /dev/null
}

for round in $(seq 1 "${ROUNDS}"); do
    echo "Round ${round}/${ROUNDS}"
    for i in $(seq 1 "${PARALLEL}"); do
        make_request &
    done
    wait
done

echo "Done."