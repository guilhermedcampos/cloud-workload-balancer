#!/usr/bin/env python3
"""
Analyze how request parameters affect instrumentation metrics.

Outputs per-workload in `<outdir>/<workload>/`:
- `correlations.csv` (Pearson r between numeric params and metrics)
- `<metric>_vs_params.png` (scatter of metric vs each numeric param with regression lines)
- `<metric>_regression_summary.csv` (coefficients, stderr, t, p if available, R2)

Usage:
  python3 scripts/analysis/analysis_metrics.py --log metrics.log --outdir dump/plots-extended

Dependencies: numpy, matplotlib; scipy is optional (for p-values).
"""
import argparse
import os
import math
import datetime
from collections import defaultdict

import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

try:
    from scipy import stats
    SCIPY_AVAILABLE = True
except Exception:
    SCIPY_AVAILABLE = False


def parse_line(line):
    parts = line.strip().split(',', 6)
    if len(parts) < 7:
        return None
    ts_str, workload, params, instr, blocks, methods, thread = parts
    try:
        ts = datetime.datetime.fromisoformat(ts_str)
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
                        params_dict[k] = nv
                    else:
                        params_dict[k] = int(v)
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


def load(path):
    rows = []
    with open(path, 'r') as f:
        for line in f:
            if not line.strip():
                continue
            r = parse_line(line)
            if r:
                rows.append(r)
    return rows


def ensure_dir(p):
    os.makedirs(p, exist_ok=True)


def pearsonr_safe(x, y):
    try:
        if SCIPY_AVAILABLE:
            r, p = stats.pearsonr(x, y)
            return r, p
        else:
            r = np.corrcoef(x, y)[0, 1]
            return float(r), None
    except Exception:
        return None, None


def regress(X, y):
    X = np.asarray(X, dtype=float)
    y = np.asarray(y, dtype=float)
    coef, *rest = np.linalg.lstsq(X, y, rcond=None)
    y_pred = X.dot(coef)
    resid = y - y_pred
    ss_res = np.sum(resid ** 2)
    ss_tot = np.sum((y - np.mean(y)) ** 2)
    r2 = 1.0 - ss_res / ss_tot if ss_tot != 0 else 0.0

    n, p = X.shape[0], X.shape[1]
    dof = max(0, n - p)
    try:
        sigma2 = ss_res / dof if dof > 0 else 0.0
        xtx_inv = np.linalg.inv(X.T.dot(X))
        stderr = np.sqrt(np.diag(xtx_inv) * sigma2)
        t_stats = coef / stderr
    except Exception:
        stderr = [None] * len(coef)
        t_stats = [None] * len(coef)

    pvals = [None] * len(coef)
    if SCIPY_AVAILABLE and dof > 0:
        for i, t in enumerate(t_stats):
            try:
                pvals[i] = 2 * stats.t.sf(abs(t), dof)
            except Exception:
                pvals[i] = None

    return {
        'coef': coef,
        'stderr': stderr,
        't': t_stats,
        'p': pvals,
        'r2': r2,
    }


def analyze(rows, outdir):
    by_workload = defaultdict(list)
    for r in rows:
        by_workload[r['workload']].append(r)

    for workload, recs in by_workload.items():
        wdir = os.path.join(outdir, workload)
        ensure_dir(wdir)
        num_reqs = len(recs)
        cmap = plt.get_cmap('tab20')
        colors_all = [cmap(i % cmap.N) for i in range(num_reqs)]

        req_map_path = os.path.join(wdir, 'requests.csv')
        with open(req_map_path, 'w') as rm:
            rm.write('idx,timestamp,params\n')
            for i, r in enumerate(recs):
                ts = r['ts'].isoformat() if r['ts'] is not None else ''
                params_s = ';'.join(f"{k}={v}" for k, v in r['params'].items())
                rm.write(f"{i},{ts},{params_s}\n")

        numeric_params = set()
        for r in recs:
            for k, v in r['params'].items():
                if isinstance(v, (int, float)):
                    numeric_params.add(k)
        numeric_params = sorted(numeric_params)

        metrics = ['instructions', 'blocks', 'methods']

        data = {m: [] for m in metrics}
        param_data = {p: [] for p in numeric_params}
        for r in recs:
            for m in metrics:
                data[m].append(r[m])
            for p in numeric_params:
                param_data[p].append(r['params'].get(p, np.nan))

        corr_lines = []
        for p in numeric_params:
            for m in metrics:
                x = np.array(param_data[p], dtype=float)
                y = np.array(data[m], dtype=float)
                mask = ~np.isnan(x) & ~np.isnan(y)
                if np.sum(mask) < 2:
                    r_val, p_val = None, None
                else:
                    r_val, p_val = pearsonr_safe(x[mask], y[mask])
                corr_lines.append((p, m, r_val, p_val))

        corr_path = os.path.join(wdir, 'correlations.csv')
        with open(corr_path, 'w') as f:
            f.write('param,metric,pearson_r,p_value\n')
            for p, m, r_val, p_val in corr_lines:
                f.write(f"{p},{m},{r_val if r_val is not None else ''},{p_val if p_val is not None else ''}\n")

        categorical_params = set()
        for r in recs:
            for k, v in r['params'].items():
                if not isinstance(v, (int, float)):
                    categorical_params.add(k)
        categorical_params = sorted(categorical_params)

        for p in categorical_params:
            for m in metrics:
                groups = {}
                for r in recs:
                    cat = r['params'].get(p, None)
                    if cat is None:
                        continue
                    groups.setdefault(cat, []).append(r[m])
                groups = {k: [v for v in vals if v is not None] for k, vals in groups.items()}
                if not groups:
                    continue
                labels = list(groups.keys())
                data_boxes = [groups[lbl] for lbl in labels]
                if not any(len(d) for d in data_boxes):
                    continue
                plt.figure(figsize=(max(6, len(labels) * 1.2), 4))
                plt.boxplot(data_boxes, labels=labels, showfliers=False)
                plt.xlabel(p)
                plt.ylabel(m)
                plt.title(f"{m} by {p} — {workload}")
                plt.tight_layout()
                outpath = os.path.join(wdir, f"{m}_by_{p}.png")
                plt.savefig(outpath)
                plt.close()
                sumcsv = os.path.join(wdir, f"{m}_by_{p}_summary.csv")
                with open(sumcsv, 'w') as sf:
                    sf.write('category,count,mean,median,std\n')
                    for lbl in labels:
                        vals = np.array(groups[lbl], dtype=float)
                        if vals.size == 0:
                            continue
                        sf.write(f"{lbl},{vals.size},{np.mean(vals)},{np.median(vals)},{np.std(vals)}\n")

        for m in metrics:
            fig_cols = min(3, max(1, len(numeric_params)))
            fig_rows = math.ceil(len(numeric_params) / fig_cols) if numeric_params else 1
            fig, axes = plt.subplots(fig_rows, fig_cols, figsize=(4 * fig_cols, 3 * fig_rows))
            if fig_rows * fig_cols == 1:
                axes = np.array([axes])
            axes = axes.flatten()
            for i, p in enumerate(numeric_params):
                ax = axes[i]
                x = np.array(param_data[p], dtype=float)
                y = np.array(data[m], dtype=float)
                mask = ~np.isnan(x) & ~np.isnan(y)
                if np.sum(mask) < 1:
                    ax.set_visible(False)
                    continue
                colors = np.array(colors_all)[mask]
                ax.scatter(x[mask], y[mask], s=20, c=colors, edgecolors='none')
                try:
                    coef = np.polyfit(x[mask], y[mask], 1)
                    xs = np.linspace(np.min(x[mask]), np.max(x[mask]), 50)
                    ax.plot(xs, np.polyval(coef, xs), color='red')
                except Exception:
                    pass
                ax.set_xlabel(p)
                ax.set_ylabel(m)
            for j in range(i+1, len(axes)):
                axes[j].set_visible(False)
            fig.suptitle(f"{m} vs params — {workload}")
            fig.tight_layout(rect=[0, 0.03, 1, 0.95])
            outpath = os.path.join(wdir, f"{m}_vs_params.png")
            fig.savefig(outpath)
            plt.close(fig)

            if numeric_params:
                X_cols = []
                for p in numeric_params:
                    X_cols.append(np.array(param_data[p], dtype=float))
                X = np.vstack(X_cols).T
                X = np.hstack([np.ones((X.shape[0], 1)), X])
                y = np.array(data[m], dtype=float)
                mask = ~np.isnan(X).any(axis=1) & ~np.isnan(y)
                if np.sum(mask) >= X.shape[1]:
                    res = regress(X[mask], y[mask])
                    sum_path = os.path.join(wdir, f"{m}_regression_summary.csv")
                    with open(sum_path, 'w') as f:
                        headers = ['term', 'coef', 'stderr', 't', 'p']
                        f.write(','.join(headers) + '\n')
                        terms = ['intercept'] + numeric_params
                        for i_term, term in enumerate(terms):
                            coef = res['coef'][i_term]
                            stderr = res['stderr'][i_term] if res['stderr'] is not None else ''
                            t = res['t'][i_term] if res['t'] is not None else ''
                            pval = res['p'][i_term] if res['p'][i_term] is not None else ''
                            f.write(f"{term},{coef},{stderr},{t},{pval}\n")
                        f.write(f"R2,,{res['r2']},,\n")

        print(f"Wrote analysis for workload: {workload} -> {wdir}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--log', '-l', default='metrics.log')
    p.add_argument('--outdir', '-o', default='dump/plots-extended')
    args = p.parse_args()

    if not os.path.isfile(args.log):
        print('Log file not found:', args.log)
        return 1

    rows = load(args.log)
    analyze(rows, args.outdir)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
