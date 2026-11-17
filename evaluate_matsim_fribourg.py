#!/usr/bin/env python3
"""
MATSim / Eqasim Evaluation & Congestion Extraction Script
Project: Tradable Mobility Credits with Dynamic Pricing (Canton of Fribourg)

Updates:
- Population / Trip plots and socio-demographic metrics strictly filter for Fribourg 
  cantonal residents (excluding external agents, through-traffic, and freight as per 
  Validation_08.pdf standards).
- Road congestion extraction (`extract_link_congestion_from_events`) includes ALL vehicles 
  on the network (residents, cross-border travelers, and freight) to ensure total physical 
  congestion is accurately measured.

run with:

python evaluate_matsim_fribourg.py \
  --sim_dir /path/to/baseline_simulation_output \
  --compare_dir /path/to/dynamic_credits_policy_output \
  --label_base "Baseline" \
  --label_comp "Dynamic Credits" \
  --out_dir ./evaluation_comparison \
  --sample_factor 10.0
"""

import os
import gzip
import argparse
import xml.etree.ElementTree as ET
from collections import defaultdict
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

# ==============================================================================
# 1. PLOT STYLING (Matching Validation_08.pdf Aesthetics)
# ==============================================================================
COLOR_BASELINE = "#4878CF"  # Muted Steel Blue (Baseline)
COLOR_POLICY = "#EE854A"    # Muted Terracotta Orange (Policy)

plt.rcParams.update({
    "font.sans-serif": "DejaVu Sans",
    "axes.edgecolor": "#333333",
    "axes.linewidth": 0.8,
    "axes.grid": False,
    "xtick.labelsize": 10,
    "ytick.labelsize": 10,
    "axes.labelsize": 11,
    "axes.titlesize": 12,
    "legend.frameon": False,
    "legend.fontsize": 9,
    "figure.dpi": 300
})

def despine(ax):
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)

# ==============================================================================
# 2. DATA LOADING & RESIDENT FILTERING
# ==============================================================================
def find_file(directory, candidates):
    for c in candidates:
        path = os.path.join(directory, c)
        if os.path.exists(path):
            return path
    return None

def load_persons_and_trips(sim_dir):
    """
    Loads person and trip tables. 
    - Filters trips and persons to include ONLY Fribourg residents inside the 
      scenario boundary (excluding external agents, freight, and through-traffic 
      for socio-demographic and mode share evaluations).
    """
    trips_file = find_file(sim_dir, [
        "eqasim_trips.csv", "eqasim_trips.csv.gz",
        "output_trips.csv.gz", "output_trips.csv"
    ])
    persons_file = find_file(sim_dir, [
        "eqasim_persons.csv", "eqasim_persons.csv.gz",
        "output_persons.csv.gz", "output_persons.csv"
    ])

    if not trips_file:
        raise FileNotFoundError(f"Could not find trips file in {sim_dir}")

    trips = pd.read_csv(trips_file, sep=None, engine="python")
    persons = pd.read_csv(persons_file, sep=None, engine="python") if persons_file else None

    # Standardize column names
    col_map_trips = {
        "person": "person_id",
        "main_mode": "mode",
        "traveled_distance": "routed_distance",
        "euclidean_distance": "crowfly_distance",
        "dep_time": "departure_time",
        "trav_time": "travel_time",
        "end_activity_type": "following_purpose"
    }
    trips.rename(columns={k: v for k, v in col_map_trips.items() if k in trips.columns and v not in trips.columns}, inplace=True)

    if persons is not None:
        if "person" in persons.columns and "person_id" not in persons.columns:
            persons.rename(columns={"person": "person_id"}, inplace=True)

    # STRICT RESIDENT FILTERING:
    # As specified in Validation_08.pdf, population and trip validation metrics 
    # must only consider residents inside the scenario boundary (excluding external/freight).
    resident_person_ids = set()
    if persons is not None:
        # Check for boundary/resident flags if present, otherwise all persons in persons table are residents
        if "in_boundary" in persons.columns:
            res_df = persons[persons["in_boundary"].astype(str).str.lower().isin(["true", "1", "yes"])]
            resident_person_ids = set(res_df["person_id"])
        elif "is_resident" in persons.columns:
            res_df = persons[persons["is_resident"].astype(str).str.lower().isin(["true", "1", "yes"])]
            resident_person_ids = set(res_df["person_id"])
        else:
            resident_person_ids = set(persons["person_id"])

        # Filter trips to only include resident persons if person_id is present
        if "person_id" in trips.columns and resident_person_ids:
            trips = trips[trips["person_id"].isin(resident_person_ids)].copy()

    # Convert distances from meters to km if needed
    for dist_col in ["crowfly_distance", "routed_distance"]:
        if dist_col in trips.columns:
            if trips[dist_col].median() > 100:
                trips[f"{dist_col}_km"] = trips[dist_col] / 1000.0
            else:
                trips[f"{dist_col}_km"] = trips[dist_col]

    # Parse departure time
    if "departure_time" in trips.columns:
        if trips["departure_time"].dtype == object:
            def parse_time(t_str):
                try:
                    parts = str(t_str).split(":")
                    return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
                except Exception:
                    return np.nan
            trips["departure_time_sec"] = trips["departure_time"].apply(parse_time)
        else:
            trips["departure_time_sec"] = trips["departure_time"]
        trips["departure_hour"] = (trips["departure_time_sec"] // 3600).clip(0, 23)

    # Harmonize Modes: Car, Public Transport, Bike, Walk
    mode_map = {
        "car": "Car", "car_passenger": "Car", "ride": "Car",
        "pt": "Public Transport", "bike": "Bike", "bicycle": "Bike",
        "walk": "Walk", "transit_walk": "Walk"
    }
    if "mode" in trips.columns:
        trips["mode_clean"] = trips["mode"].astype(str).str.lower().map(lambda m: mode_map.get(m, "Other"))
        trips = trips[trips["mode_clean"].isin(["Car", "Public Transport", "Bike", "Walk"])].copy()

    # Distance Bins
    dist_col_use = "crowfly_distance_km" if "crowfly_distance_km" in trips.columns else "routed_distance_km"
    if dist_col_use in trips.columns:
        bins = [0, 2, 5, 10, 20, 50, np.inf]
        labels = ["0-2km", "2-5km", "5-10km", "10-20km", "20-50km", "50km+"]
        trips["distance_bin"] = pd.cut(trips[dist_col_use], bins=bins, labels=labels, right=False)

    # Merge person attributes
    if persons is not None:
        if "household_income" in persons.columns or "income" in persons.columns:
            inc_col = "household_income" if "household_income" in persons.columns else "income"
            persons["income_val"] = pd.to_numeric(persons[inc_col], errors="coerce")
            persons["income_group"] = pd.cut(
                persons["income_val"],
                bins=[-np.inf, 6000, 12000, np.inf],
                labels=["low (<6k)", "middle (6-12k)", "high (>12k)"]
            )

        if "income_per_capita" in persons.columns:
            ipc_col = "income_per_capita"
        elif "income_val" in persons.columns:
            hh_size = persons.get("household_size", pd.Series(1, index=persons.index)).clip(lower=1)
            eq_factor = 1.0 + 0.5 * (hh_size - 1)
            persons["income_per_capita"] = persons["income_val"] / eq_factor
            ipc_col = "income_per_capita"
        else:
            ipc_col = None

        if ipc_col:
            persons["income_pc_bin"] = pd.cut(
                persons[ipc_col],
                bins=[-np.inf, 2500, 5000, 7500, 10000, np.inf],
                labels=["0-2.5k CHF", "2.5-5k CHF", "5-7.5k CHF", "7.5-10k CHF", ">10k CHF"]
            )

        if "car_availability" in persons.columns or "carAvail" in persons.columns:
            ca_col = "car_availability" if "car_availability" in persons.columns else "carAvail"
            persons["car_avail_clean"] = persons[ca_col].astype(str).str.lower().map(
                lambda x: "never" if "never" in x or x == "none" or x == "false" else "available"
            )

        for area_col in ["municipality_type", "area_type", "spatial_type"]:
            if area_col in persons.columns:
                persons["area_type_clean"] = persons[area_col].astype(str).str.lower().map(
                    lambda x: "rural" if "rural" in x else "urban/suburban"
                )
                break

        trips = trips.merge(persons, on="person_id", how="left", suffixes=("", "_person"))

    return persons, trips

# ==============================================================================
# 3. ROAD CONGESTION EXTRACTION (INCLUDES EVERYONE: Residents + Freight + External)
# ==============================================================================
def parse_network_xml(network_path):
    open_fn = gzip.open if network_path.endswith(".gz") else open
    links = {}
    with open_fn(network_path, "rb") as f:
        context = ET.iterparse(f, events=("start", "end"))
        for event, elem in context:
            if event == "end" and elem.tag == "link":
                lid = elem.attrib.get("id")
                if lid and not lid.startswith("pt_"):
                    length = float(elem.attrib.get("length", 1.0))
                    freespeed = float(elem.attrib.get("freespeed", 13.89))
                    capacity = float(elem.attrib.get("capacity", 1000.0))
                    modes = elem.attrib.get("modes", "car")
                    if "car" in modes:
                        links[lid] = {
                            "link_id": lid,
                            "length_m": length,
                            "freespeed_ms": max(freespeed, 0.1),
                            "free_time_s": length / max(freespeed, 0.1),
                            "capacity_vh": capacity
                        }
                elem.clear()
    return pd.DataFrame.from_dict(links, orient="index")

def extract_link_congestion_from_events(events_path, network_df, sample_factor=10.0, bin_size_s=3600):
    """
    Parses output_events.xml(.gz) including ALL vehicles (residents, through-traffic, 
    and freight) to calculate the primary road congestion metric: 
    Travel Time Index (TTI).
    """
    open_fn = gzip.open if events_path.endswith(".gz") else open
    valid_links = set(network_df["link_id"].unique())
    enter_times = {}
    stats = defaultdict(lambda: [0, 0.0])

    with open_fn(events_path, "rb") as f:
        context = ET.iterparse(f, events=("end",))
        for event, elem in context:
            if elem.tag == "event":
                etype = elem.attrib.get("type")
                if etype == "entered link":
                    lid = elem.attrib.get("link")
                    if lid in valid_links:
                        vid = elem.attrib.get("vehicle")
                        t = float(elem.attrib.get("time", 0.0))
                        enter_times[(vid, lid)] = t
                elif etype == "left link":
                    lid = elem.attrib.get("link")
                    if lid in valid_links:
                        vid = elem.attrib.get("vehicle")
                        t_leave = float(elem.attrib.get("time", 0.0))
                        t_enter = enter_times.pop((vid, lid), None)
                        if t_enter is not None:
                            trav_time = max(t_leave - t_enter, 0.0)
                            time_bin = int(t_enter // bin_size_s)
                            if 0 <= time_bin < 24:
                                rec = stats[(lid, time_bin)]
                                rec[0] += 1
                                rec[1] += trav_time
                elif etype == "vehicle leaves traffic":
                    lid = elem.attrib.get("link")
                    vid = elem.attrib.get("vehicle")
                    enter_times.pop((vid, lid), None)
                elem.clear()

    rows = []
    net_lookup = network_df.set_index("link_id").to_dict(orient="index")

    for (lid, hour), (sim_count, sum_tt) in stats.items():
        net_info = net_lookup[lid]
        free_tt = net_info["free_time_s"]
        length_m = net_info["length_m"]
        cap_vh = net_info["capacity_vh"]

        avg_tt = max(sum_tt / sim_count, free_tt) if sim_count > 0 else free_tt
        delay_per_veh_s = max(0.0, avg_tt - free_tt)
        scaled_volume = sim_count * sample_factor
        total_delay_veh_hours = (delay_per_veh_s * scaled_volume) / 3600.0

        # PRIMARY CONGESTION METRIC: Travel Time Index (TTI)
        tti = avg_tt / free_tt if free_tt > 0 else 1.0

        rows.append({
            "link_id": lid,
            "hour": hour,
            "sim_vehicles": sim_count,
            "scaled_volume_vh": scaled_volume,
            "capacity_vh": cap_vh,
            "length_m": length_m,
            "free_travel_time_s": round(free_tt, 3),
            "avg_travel_time_s": round(avg_tt, 3),
            "avg_delay_s": round(delay_per_veh_s, 3),
            "total_delay_veh_h": round(total_delay_veh_hours, 4),
            "tti": round(tti, 4)
        })

    hourly_df = pd.DataFrame(rows)
    if hourly_df.empty:
        return hourly_df, pd.DataFrame()

    daily_agg = hourly_df.groupby("link_id").apply(
        lambda g: pd.Series({
            "length_m": g["length_m"].iloc[0],
            "capacity_vh": g["capacity_vh"].iloc[0],
            "free_travel_time_s": g["free_travel_time_s"].iloc[0],
            "daily_scaled_volume": g["scaled_volume_vh"].sum(),
            "daily_total_delay_veh_h": g["total_delay_veh_h"].sum(),
            "weighted_avg_tti": np.average(g["tti"], weights=g["scaled_volume_vh"]) if g["scaled_volume_vh"].sum() > 0 else 1.0,
            "max_hourly_tti": g["tti"].max(),
            "peak_congested_hour": g.loc[g["tti"].idxmax(), "hour"]
        })
    ).reset_index()

    return hourly_df, daily_agg

# ==============================================================================
# 4. METRIC EXTRACTION & CSV EXPORT
# ==============================================================================
def compute_and_export_metrics(persons, trips, hourly_cong, daily_cong, out_dir, sample_factor=10.0):
    os.makedirs(out_dir, exist_ok=True)
    metrics_dict = {}

    total_trips_sim = len(trips)
    car_trips = trips[trips["mode_clean"] == "Car"]
    dist_col = "routed_distance_km" if "routed_distance_km" in trips.columns else "crowfly_distance_km"
    total_vkt = car_trips[dist_col].sum() * sample_factor if dist_col in car_trips.columns else np.nan
    total_delay_vh = daily_cong["daily_total_delay_veh_h"].sum() if not daily_cong.empty else np.nan

    kpi_df = pd.DataFrame([{
        "total_resident_trips": total_trips_sim,
        "scaled_resident_trips": total_trips_sim * sample_factor,
        "scaled_resident_car_vkt_km": round(total_vkt, 2) if pd.notna(total_vkt) else np.nan,
        "total_network_delay_veh_h": round(total_delay_vh, 2) if pd.notna(total_delay_vh) else np.nan
    }])
    kpi_df.to_csv(os.path.join(out_dir, "summary_kpis.csv"), index=False)
    metrics_dict["kpis"] = kpi_df

    mode_order = ["Car", "Public Transport", "Bike", "Walk"]
    ms_general = (
        trips["mode_clean"]
        .value_counts(normalize=True)
        .reindex(mode_order, fill_value=0.0) * 100.0
    ).rename("mode_share_pct").reset_index().rename(columns={"index": "mode", "mode_clean": "mode"})
    ms_general.to_csv(os.path.join(out_dir, "mode_share_general.csv"), index=False)
    metrics_dict["ms_general"] = ms_general

    if "distance_bin" in trips.columns:
        ms_dist = (
            pd.crosstab(trips["distance_bin"], trips["mode_clean"], normalize="index")
            .reindex(columns=mode_order, fill_value=0.0) * 100.0
        ).reset_index()
        ms_dist.to_csv(os.path.join(out_dir, "mode_share_by_distance.csv"), index=False)
        metrics_dict["ms_dist"] = ms_dist

    if "income_pc_bin" in trips.columns:
        ms_inc = (
            pd.crosstab(trips["income_pc_bin"], trips["mode_clean"], normalize="index")
            .reindex(columns=mode_order, fill_value=0.0) * 100.0
        ).reset_index()
        ms_inc.to_csv(os.path.join(out_dir, "mode_share_by_income_pc.csv"), index=False)
        metrics_dict["ms_inc"] = ms_inc

    if "car_avail_clean" in trips.columns:
        ms_ca = (
            pd.crosstab(trips["car_avail_clean"], trips["mode_clean"], normalize="index")
            .reindex(index=["available", "never"], columns=mode_order, fill_value=0.0) * 100.0
        ).reset_index()
        ms_ca.to_csv(os.path.join(out_dir, "mode_share_by_car_availability.csv"), index=False)
        metrics_dict["ms_ca"] = ms_ca

    if "area_type_clean" in trips.columns:
        ms_area = (
            pd.crosstab(trips["area_type_clean"], trips["mode_clean"], normalize="index")
            .reindex(index=["urban/suburban", "rural"], columns=mode_order, fill_value=0.0) * 100.0
        ).reset_index()
        ms_area.to_csv(os.path.join(out_dir, "mode_share_by_area_type.csv"), index=False)
        metrics_dict["ms_area"] = ms_area

    if "departure_hour" in trips.columns:
        hours = pd.Index(range(24), name="departure_hour")
        dep_all = (trips["departure_hour"].value_counts(normalize=True).reindex(hours, fill_value=0.0) * 100.0).rename("all_trips_pct")
        dep_car = (trips[trips["mode_clean"] == "Car"]["departure_hour"].value_counts(normalize=True).reindex(hours, fill_value=0.0) * 100.0).rename("car_pct")
        dep_pt = (trips[trips["mode_clean"] == "Public Transport"]["departure_hour"].value_counts(normalize=True).reindex(hours, fill_value=0.0) * 100.0).rename("pt_pct")
        dep_hourly = pd.concat([dep_all, dep_car, dep_pt], axis=1).reset_index()
        dep_hourly.to_csv(os.path.join(out_dir, "departure_time_hourly.csv"), index=False)
        metrics_dict["dep_hourly"] = dep_hourly

    if persons is not None and "income_group" in persons.columns:
        for sc_col in ["executed_score", "score", "selected_plan_score"]:
            if sc_col in persons.columns:
                persons["score_num"] = pd.to_numeric(persons[sc_col], errors="coerce")
                fairness_df = persons.groupby("income_group", observed=False)["score_num"].agg(
                    count="count", mean_utility="mean", median_utility="median", std_utility="std"
                ).reset_index()
                fairness_df.to_csv(os.path.join(out_dir, "utility_by_income_group.csv"), index=False)
                metrics_dict["fairness"] = fairness_df
                break

    if not hourly_cong.empty:
        hourly_cong.to_csv(os.path.join(out_dir, "link_congestion_hourly.csv"), index=False)
        daily_cong.to_csv(os.path.join(out_dir, "link_congestion_daily_summary.csv"), index=False)

    return metrics_dict

# ==============================================================================
# 5. PLOTTING FUNCTIONS
# ==============================================================================
def plot_grouped_bars(df_base, df_comp, x_col, y_col, title, ylabel, out_path, label_base="Baseline", label_comp="Policy"):
    fig, ax = plt.subplots(figsize=(6.5, 4.2))
    x_labels = df_base[x_col].astype(str).tolist()
    x = np.arange(len(x_labels))
    if df_comp is not None:
        width = 0.38
        ax.bar(x - width/2, df_base[y_col], width, label=label_base, color=COLOR_BASELINE)
        ax.bar(x + width/2, df_comp[y_col], width, label=label_comp, color=COLOR_POLICY)
    else:
        width = 0.55
        ax.bar(x, df_base[y_col], width, label=label_base, color=COLOR_POLICY)
    ax.set_ylabel(ylabel)
    ax.set_title(title, pad=10)
    ax.set_xticks(x)
    ax.set_xticklabels(x_labels)
    ax.set_ylim(0, max(df_base[y_col].max(), df_comp[y_col].max() if df_comp is not None else 0) * 1.18 + 2)
    ax.legend(loc="upper right")
    despine(ax)
    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)

def plot_2x2_mode_grid(df_base, df_comp, x_col, title_suffix, out_path, label_base="Baseline", label_comp="Policy"):
    modes = ["Car", "Public Transport", "Bike", "Walk"]
    fig, axes = plt.subplots(2, 2, figsize=(11, 7.5))
    axes = axes.flatten()
    x_labels = df_base[x_col].astype(str).tolist()
    x = np.arange(len(x_labels))
    for i, mode in enumerate(modes):
        ax = axes[i]
        y_base = df_base[mode].values
        if df_comp is not None and mode in df_comp.columns:
            y_comp = df_comp[mode].values
            width = 0.38
            ax.bar(x - width/2, y_base, width, label=label_base, color=COLOR_BASELINE)
            ax.bar(x + width/2, y_comp, width, label=label_comp, color=COLOR_POLICY)
            max_y = max(y_base.max(), y_comp.max())
        else:
            width = 0.55
            ax.bar(x, y_base, width, label=label_base, color=COLOR_POLICY)
            max_y = y_base.max()
        ax.set_title(f"{mode} mode share, {title_suffix}")
        ax.set_ylabel("Mode share (%)")
        ax.set_xticks(x)
        ax.set_xticklabels(x_labels)
        ax.set_ylim(0, max(max_y * 1.2, 5.0))
        if i == 0:
            ax.legend(loc="upper right")
        despine(ax)
    fig.tight_layout(pad=2.0)
    fig.savefig(out_path)
    plt.close(fig)

def plot_departure_profiles(df_base, df_comp, out_path, label_base="Baseline", label_comp="Policy"):
    fig, axes = plt.subplots(1, 3, figsize=(13, 3.8))
    cols = [("all_trips_pct", "all trips"), ("car_pct", "car"), ("pt_pct", "pt")]
    for i, (col, title) in enumerate(cols):
        ax = axes[i]
        ax.plot(df_base["departure_hour"], df_base[col], label=label_base, color=COLOR_BASELINE if df_comp is not None else COLOR_POLICY, linewidth=1.8)
        if df_comp is not None and col in df_comp.columns:
            ax.plot(df_comp["departure_hour"], df_comp[col], label=label_comp, color=COLOR_POLICY, linewidth=1.8)
        ax.set_title(title)
        ax.set_xlabel("departure hour")
        if i == 0:
            ax.set_ylabel("share of trips [%]")
            ax.legend(loc="lower center")
        despine(ax)
    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)

def plot_spatial_car_share_heatmap(trips, out_path, patch_size_m=2000):
    x_col, y_col = None, None
    for c in ["origin_x", "start_x", "from_x"]:
        if c in trips.columns: x_col = c
    for c in ["origin_y", "start_y", "from_y"]:
        if c in trips.columns: y_col = c
    if not x_col or not y_col: return

    df = trips[[x_col, y_col, "mode_clean"]].dropna().copy()
    if df.empty: return
    df["e_bin"] = (np.floor(df[x_col] / patch_size_m) * (patch_size_m / 1000.0))
    df["n_bin"] = (np.floor(df[y_col] / patch_size_m) * (patch_size_m / 1000.0))
    df["is_car"] = (df["mode_clean"] == "Car").astype(int)
    grid = df.groupby(["n_bin", "e_bin"])["is_car"].agg(["count", "mean"]).reset_index()
    grid = grid[grid["count"] >= 3]
    grid["car_share_pct"] = grid["mean"] * 100.0
    pivot = grid.pivot(index="n_bin", columns="e_bin", values="car_share_pct")
    if pivot.empty: return

    fig, ax = plt.subplots(figsize=(5.5, 5.5))
    ax.set_facecolor("#E0E0E0")
    cmap = plt.cm.YlOrRd
    cmap.set_bad(color="#E0E0E0")
    extent = [
        pivot.columns.min(), pivot.columns.max() + (patch_size_m / 1000.0),
        pivot.index.min(), pivot.index.max() + (patch_size_m / 1000.0)
    ]
    im = ax.imshow(pivot.values, origin="lower", extent=extent, cmap=cmap, vmin=0, vmax=100, aspect="equal")
    cbar = fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    cbar.set_label("car share of trips [%]")
    ax.set_xlabel("E [km] (LV95)")
    ax.set_ylabel("N [km] (LV95)")
    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)

def plot_hourly_network_congestion(hourly_cong_base, hourly_cong_comp, out_path, label_base="Baseline", label_comp="Policy"):
    if hourly_cong_base.empty: return
    def agg_hour(df):
        hours = pd.Index(range(24), name="hour")
        grp = df.groupby("hour").apply(
            lambda g: pd.Series({
                "delay_vh": g["total_delay_veh_h"].sum(),
                "mean_tti": np.average(g["tti"], weights=g["scaled_volume_vh"]) if g["scaled_volume_vh"].sum() > 0 else 1.0
            })
        ).reindex(hours, fill_value=0.0)
        grp.loc[grp["mean_tti"] == 0, "mean_tti"] = 1.0
        return grp.reset_index()

    b_agg = agg_hour(hourly_cong_base)
    c_agg = agg_hour(hourly_cong_comp) if hourly_cong_comp is not None and not hourly_cong_comp.empty else None

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    ax1 = axes[0]
    ax1.plot(b_agg["hour"], b_agg["delay_vh"], label=label_base, color=COLOR_BASELINE if c_agg is not None else COLOR_POLICY, linewidth=2.0)
    if c_agg is not None:
        ax1.plot(c_agg["hour"], c_agg["delay_vh"], label=label_comp, color=COLOR_POLICY, linewidth=2.0)
    ax1.set_title("Network Congestion Delay by Hour (All Vehicles)")
    ax1.set_xlabel("Hour of Day")
    ax1.set_ylabel("Total Delay [Vehicle-Hours]")
    ax1.legend(loc="upper left")
    despine(ax1)

    ax2 = axes[1]
    ax2.plot(b_agg["hour"], b_agg["mean_tti"], label=label_base, color=COLOR_BASELINE if c_agg is not None else COLOR_POLICY, linewidth=2.0)
    if c_agg is not None:
        ax2.plot(c_agg["hour"], c_agg["mean_tti"], label=label_comp, color=COLOR_POLICY, linewidth=2.0)
    ax2.set_title("Volume-Weighted Travel Time Index (TTI)")
    ax2.set_xlabel("Hour of Day")
    ax2.set_ylabel("Travel Time Index (t_actual / t_free)")
    despine(ax2)

    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)

# ==============================================================================
# 6. MAIN CLI ENTRYPOINT
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(description="Extract MATSim/eqasim metrics (residents only) & congestion (everyone).")
    parser.add_argument("--sim_dir", required=True, help="Path to baseline MATSim simulation output folder.")
    parser.add_argument("--compare_dir", default=None, help="Optional path to policy implementation output folder.")
    parser.add_argument("--label_base", default="Baseline", help="Legend label for baseline.")
    parser.add_argument("--label_comp", default="Policy", help="Legend label for comparison.")
    parser.add_argument("--out_dir", default="./evaluation_output", help="Output directory.")
    parser.add_argument("--sample_factor", type=float, default=10.0, help="Sample factor (10.0 for 10% sample).")
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    print(f"[1/4] Loading Fribourg residents and trips from {args.sim_dir}...")
    persons_base, trips_base = load_persons_and_trips(args.sim_dir)

    net_file = find_file(args.sim_dir, ["output_network.xml.gz", "output_network.xml"])
    events_file = find_file(args.sim_dir, ["output_events.xml.gz", "output_events.xml"])

    hourly_cong_base, daily_cong_base = pd.DataFrame(), pd.DataFrame()
    if net_file and events_file:
        print(f"[2/4] Extracting link congestion (INCLUDES EVERYONE: residents, freight, cross-border)...")
        net_df = parse_network_xml(net_file)
        hourly_cong_base, daily_cong_base = extract_link_congestion_from_events(
            events_file, net_df, sample_factor=args.sample_factor
        )

    print(f"[3/4] Exporting metrics CSVs...")
    metrics_base = compute_and_export_metrics(
        persons_base, trips_base, hourly_cong_base, daily_cong_base,
        out_dir=os.path.join(args.out_dir, "csv_baseline"),
        sample_factor=args.sample_factor
    )

    metrics_comp = {}
    hourly_cong_comp = pd.DataFrame()
    if args.compare_dir:
        print(f"[3b/4] Loading comparison scenario...")
        persons_comp, trips_comp = load_persons_and_trips(args.compare_dir)
        net_c = find_file(args.compare_dir, ["output_network.xml.gz", "output_network.xml"])
        ev_c = find_file(args.compare_dir, ["output_events.xml.gz", "output_events.xml"])
        daily_cong_comp = pd.DataFrame()
        if net_c and ev_c:
            net_df_c = parse_network_xml(net_c)
            hourly_cong_comp, daily_cong_comp = extract_link_congestion_from_events(
                ev_c, net_df_c, sample_factor=args.sample_factor
            )
        metrics_comp = compute_and_export_metrics(
            persons_comp, trips_comp, hourly_cong_comp, daily_cong_comp,
            out_dir=os.path.join(args.out_dir, "csv_policy"),
            sample_factor=args.sample_factor
        )

    print(f"[4/4] Generating validation plots...")
    plots_dir = os.path.join(args.out_dir, "plots")
    os.makedirs(plots_dir, exist_ok=True)

    plot_grouped_bars(
        metrics_base["ms_general"], metrics_comp.get("ms_general"),
        x_col="mode", y_col="mode_share_pct",
        title="General Mode Share Distribution (Residents Only)",
        ylabel="Mode share (%)",
        out_path=os.path.join(plots_dir, "01_mode_share_general.png"),
        label_base=args.label_base, label_comp=args.label_comp
    )

    if "ms_dist" in metrics_base:
        plot_2x2_mode_grid(
            metrics_base["ms_dist"], metrics_comp.get("ms_dist"),
            x_col="distance_bin", title_suffix="by distance (residents)",
            out_path=os.path.join(plots_dir, "02_mode_share_by_distance.png"),
            label_base=args.label_base, label_comp=args.label_comp
        )

    if "ms_inc" in metrics_base:
        plot_2x2_mode_grid(
            metrics_base["ms_inc"], metrics_comp.get("ms_inc"),
            x_col="income_pc_bin", title_suffix="by income per capita (residents)",
            out_path=os.path.join(plots_dir, "03_mode_share_by_income.png"),
            label_base=args.label_base, label_comp=args.label_comp
        )

    if "ms_ca" in metrics_base:
        plot_2x2_mode_grid(
            metrics_base["ms_ca"], metrics_comp.get("ms_ca"),
            x_col="car_avail_clean", title_suffix="by car availability (residents)",
            out_path=os.path.join(plots_dir, "04_mode_share_by_car_availability.png"),
            label_base=args.label_base, label_comp=args.label_comp
        )

    if "ms_area" in metrics_base:
        plot_2x2_mode_grid(
            metrics_base["ms_area"], metrics_comp.get("ms_area"),
            x_col="area_type_clean", title_suffix="by area type (residents)",
            out_path=os.path.join(plots_dir, "05_mode_share_by_area_type.png"),
            label_base=args.label_base, label_comp=args.label_comp
        )

    if "dep_hourly" in metrics_base:
        plot_departure_profiles(
            metrics_base["dep_hourly"], metrics_comp.get("dep_hourly"),
            out_path=os.path.join(plots_dir, "06_departure_time_profiles.png"),
            label_base=args.label_base, label_comp=args.label_comp
        )

    plot_spatial_car_share_heatmap(
        trips_base,
        out_path=os.path.join(plots_dir, "07_spatial_car_share_2x2km_baseline.png")
    )

    if not hourly_cong_base.empty:
        plot_hourly_network_congestion(
            hourly_cong_base, hourly_cong_comp if not hourly_cong_comp.empty else None,
            out_path=os.path.join(plots_dir, "08_network_congestion_hourly.png"),
            label_base=args.label_base, label_comp=args.label_comp
        )

    print("Evaluation complete successfully!")

if __name__ == "__main__":
    main()
