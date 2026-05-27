import pandas as pd
import matplotlib.pyplot as plt

LOG_FILE = "metrics.log"


def parse_params(param_str):
    d = {}
    for p in param_str.split(";"):
        if "=" not in p:
            continue
        k, v = p.split("=")

        if v == "true":
            d[k] = 1
        elif v == "false":
            d[k] = 0
        else:
            try:
                d[k] = float(v)
            except:
                pass
    return d


# ---------------------------
# LOAD DATA
# ---------------------------
rows = []

with open(LOG_FILE) as f:
    for line in f:
        line = line.strip()
        if not line:
            continue

        parts = line.split(",")
        if len(parts) < 3:
            continue

        workload = parts[1]
        params = parse_params(parts[2])

        inst_part = [x for x in parts if "instructions=" in x]
        if not inst_part:
            continue

        instructions = float(inst_part[0].split("=")[1])

        row = {
            "workload": workload,
            "instructions": instructions
        }

        row.update(params)
        rows.append(row)

df = pd.DataFrame(rows)


# ---------------------------
# PLOTS
# ---------------------------
def plot_dna():
    sub = df[df.workload == "dna"].copy()
    if sub.empty:
        return

    sub["seq_sum"] = sub["seq1Length"] + sub["seq2Length"]

    # 1) fix minLength, vary seq
    fixed_min = sub[sub["minLength"] == sub["minLength"].median()]
    fixed_min = fixed_min.sort_values("seq_sum")

    plt.figure()
    plt.plot(fixed_min["seq_sum"], fixed_min["instructions"], marker="o")
    plt.title("DNA: seq length impact (minLength fixed)")
    plt.xlabel("seq1 + seq2 length")
    plt.ylabel("instructions")
    plt.grid()
    plt.show()

    # 2) fix seq, vary minLength
    fixed_seq = sub[sub["seq_sum"] == sub["seq_sum"].median()]
    fixed_seq = fixed_seq.sort_values("minLength")

    plt.figure()
    plt.plot(fixed_seq["minLength"], fixed_seq["instructions"], marker="o")
    plt.title("DNA: minLength impact (seq fixed)")
    plt.xlabel("minLength")
    plt.ylabel("instructions")
    plt.grid()
    plt.show()


def plot_fractals():
    sub = df[df.workload == "fractals"].copy()
    if sub.empty:
        return

    sub["area"] = sub["w"] * sub["h"]

    # 3) fix area, vary iterations
    fixed_area = sub[sub["area"] == sub["area"].median()]
    fixed_area = fixed_area.sort_values("iterations")

    plt.figure()
    plt.plot(fixed_area["iterations"], fixed_area["instructions"], marker="o")
    plt.title("Fractals: iterations impact (area fixed)")
    plt.xlabel("iterations")
    plt.ylabel("instructions")
    plt.grid()
    plt.show()

    # 4) fix iterations, vary area
    fixed_it = sub[sub["iterations"] == sub["iterations"].median()]
    fixed_it = fixed_it.sort_values("area")

    plt.figure()
    plt.plot(fixed_it["area"], fixed_it["instructions"], marker="o")
    plt.title("Fractals: resolution impact (iterations fixed)")
    plt.xlabel("w*h (area)")
    plt.ylabel("instructions")
    plt.grid()
    plt.show()


def plot_grayscott():
    sub = df[df.workload == "grayscott"].copy()
    if sub.empty:
        return

    # 5) fix size, vary iterations
    fixed_size = sub[sub["size"] == sub["size"].median()]
    fixed_size = fixed_size.sort_values("maxIterations")

    plt.figure()
    plt.plot(fixed_size["maxIterations"], fixed_size["instructions"], marker="o")
    plt.title("GrayScott: iterations impact (size fixed)")
    plt.xlabel("maxIterations")
    plt.ylabel("instructions")
    plt.grid()
    plt.show()

    # 6) fix iterations, vary size
    fixed_it = sub[sub["maxIterations"] == sub["maxIterations"].median()]
    fixed_it = fixed_it.sort_values("size")

    plt.figure()
    plt.plot(fixed_it["size"], fixed_it["instructions"], marker="o")
    plt.title("GrayScott: size impact (iterations fixed)")
    plt.xlabel("size")
    plt.ylabel("instructions")
    plt.grid()
    plt.show()


# ---------------------------
# RUN
# ---------------------------
plot_dna()
plot_fractals()
plot_grayscott()