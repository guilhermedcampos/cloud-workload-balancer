#!/usr/bin/env python3
"""
Parse `metrics.log` and plot instrumentation metrics (instructions, blocks, methods).

Usage:
  python3 scripts/analysis/plot_metrics.py --log metrics.log --outdir dump/plots

Produces PNGs: `instructions.png`, `blocks.png`, `methods.png` (one line per workload).
"""
import argparse
import datetime
import os
from collections import defaultdict

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def parse_line(line):
    parts = line.strip().split(',', 6)
    if len(parts) < 7:
        return None
    ts_str, workload, params, instr, blocks, methods, thread = parts
    try:
        ts = datetime.datetime.fromisoformat(ts_str)
    except Exception:
        try:
            ts = datetime.datetime.strptime(ts_str, "%Y-%m-%dT%H:%M:%S.%f")
        except Exception:
            ts = None

    def extract_num(token):
        if token is None:
            return None
        if '=' in token:
            token = token.split('=', 1)[1]
        try:
            return int(token)
        except Exception:
            try:
                return int(float(token))
            except Exception:
                return None

    instr = extract_num(instr)
    blocks = extract_num(blocks)
    methods = extract_num(methods)

    params_dict = {}
    try:
        for part in params.split(';'):
            if not part:
                continue
            if '=' in part:
                k, v = part.split('=', 1)
                try:
                    if '.' in v:
                        nv = float(v)
                    else:
                        nv = int(v)
                    params_dict[k] = nv
                except Exception:
                    params_dict[k] = v
            else:
                params_dict[part] = True
    except Exception:
        params_dict = {}

    return {
        'ts': ts,
        'workload': workload,
        'params': params_dict,
        'instructions': instr,
        'blocks': blocks,
        'methods': methods,
        'thread': thread,
    }


def load_metrics(path):
    by_workload = defaultdict(list)
    with open(path, 'r') as f:
        for line in f:
            if not line.strip():
                continue
            rec = parse_line(line)
            if not rec:
                continue
            by_workload[rec['workload']].append(rec)
    for w in by_workload:
        by_workload[w].sort(key=lambda r: (r['ts'] or datetime.datetime.min))
    return by_workload


def plot_metric(by_workload, metric, outpath):
    plt.figure(figsize=(10, 4))
    any_data = False
    for workload, rows in sorted(by_workload.items()):
        xs = [r['ts'] for r in rows if r[metric] is not None and r['ts'] is not None]
        ys = [r[metric] for r in rows if r[metric] is not None and r['ts'] is not None]
        if not xs:
            continue
        any_data = True
        plt.plot(xs, ys, marker='o', linestyle='-', label=workload)
    if not any_data:
        print(f"No data for metric '{metric}' — skipping {outpath}")
        return False
    plt.xlabel('Time')
    plt.ylabel(metric.capitalize())
    plt.title(f"{metric.capitalize()} over time by workload")
    plt.legend()
    plt.tight_layout()
    plt.savefig(outpath)
    plt.close()
    return True


def plot_metric_vs_param(by_workload, metric, outdir):
    for workload, rows in sorted(by_workload.items()):
        param_values = {}
        for r in rows:
            for k, v in r['params'].items():
                if isinstance(v, (int, float)):
                    param_values.setdefault(k, []).append((v, r[metric]))

        if not param_values:
            continue

        workdir = os.path.join(outdir, workload)
        os.makedirs(workdir, exist_ok=True)

        for param, pairs in param_values.items():
            pairs = [(x, y) for x, y in pairs if y is not None]
            if not pairs:
                continue
            pairs.sort(key=lambda p: p[0])
            xs = [p[0] for p in pairs]
            ys = [p[1] for p in pairs]

            plt.figure(figsize=(8, 4))
            plt.plot(xs, ys, marker='o', linestyle='-')
            plt.xlabel(param)
            plt.ylabel(metric.capitalize())
            plt.title(f"{metric} vs {param} — {workload}")
            plt.tight_layout()
            outpath = os.path.join(workdir, f"{metric}_vs_{param}.png")
            plt.savefig(outpath)
            plt.close()
            print(f"Wrote: {outpath}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--log', '-l', default='metrics.log', help='Path to metrics.log')
    p.add_argument('--outdir', '-o', default='dump/plots', help='Output directory for PNGs')
    args = p.parse_args()

    if not os.path.isfile(args.log):
        print(f"Log file not found: {args.log}")
        return 1

    os.makedirs(args.outdir, exist_ok=True)
    by_workload = load_metrics(args.log)

    metrics = ['instructions', 'blocks', 'methods']
    for m in metrics:
        outpath = os.path.join(args.outdir, f"{m}.png")
        ok = plot_metric(by_workload, m, outpath)
        if ok:
            print(f"Wrote: {outpath}")

    for m in metrics:
        plot_metric_vs_param(by_workload, m, args.outdir)

    return 0


if __name__ == '__main__':
    raise SystemExit(main())
