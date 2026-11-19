"use client";

import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { FunnelStage, GroupedRate, WeeklyPoint } from "@/lib/insights";

/**
 * Charts share one palette and one set of axis conventions so the dashboard
 * reads as a single surface. Every chart has a text equivalent nearby, so a
 * screen reader user is never dependent on the drawing.
 */
const BRAND = "oklch(58% 0.19 272)";
const MUTED = "oklch(72% 0.015 265)";
const GRID = "oklch(62% 0.01 265 / 0.25)";

const axisStyle = { fontSize: 12, fill: MUTED };

function tooltipStyle() {
  return {
    contentStyle: {
      background: "var(--color-surface)",
      border: "1px solid var(--color-border)",
      borderRadius: "0.5rem",
      fontSize: "0.8rem",
      color: "var(--color-fg)",
    },
    cursor: { fill: "oklch(62% 0.01 265 / 0.12)" },
  };
}

export function WeeklyApplicationsChart({ data }: { data: WeeklyPoint[] }) {
  const shaped = data.map((point) => ({
    week: point.week.slice(5),
    applied: point.applied,
  }));

  return (
    <div className="h-64 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={shaped} margin={{ top: 8, right: 8, bottom: 8, left: -16 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="week" tick={axisStyle} tickLine={false} axisLine={false} />
          <YAxis allowDecimals={false} tick={axisStyle} tickLine={false} axisLine={false} />
          <Tooltip {...tooltipStyle()} />
          <Bar dataKey="applied" fill={BRAND} radius={[4, 4, 0, 0]} name="Applications" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function FunnelChart({ data }: { data: FunnelStage[] }) {
  return (
    <div className="h-64 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ top: 8, right: 32, bottom: 8, left: 8 }}>
          <CartesianGrid stroke={GRID} horizontal={false} />
          <XAxis type="number" allowDecimals={false} tick={axisStyle} tickLine={false} axisLine={false} />
          <YAxis
            type="category"
            dataKey="stage"
            width={92}
            tick={axisStyle}
            tickLine={false}
            axisLine={false}
          />
          <Tooltip {...tooltipStyle()} />
          <Bar dataKey="count" fill={BRAND} radius={[0, 4, 4, 0]} name="Applications">
            <LabelList dataKey="count" position="right" style={{ fontSize: 12, fill: MUTED }} />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

export function ResponseRateChart({ data }: { data: GroupedRate[] }) {
  const shaped = data
    .filter((group) => group.applications >= 2)
    .slice(0, 8)
    .map((group) => ({
      group: group.group,
      rate: group.responseRate === null ? 0 : Math.round(group.responseRate * 100),
      applications: group.applications,
    }));

  if (shaped.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-fg-muted">
        Not enough applications yet to compare - a rate over one application means nothing.
      </p>
    );
  }

  return (
    <div className="h-64 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={shaped} margin={{ top: 8, right: 8, bottom: 8, left: -16 }}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="group" tick={axisStyle} tickLine={false} axisLine={false} />
          <YAxis
            unit="%"
            domain={[0, 100]}
            tick={axisStyle}
            tickLine={false}
            axisLine={false}
          />
          <Tooltip
            {...tooltipStyle()}
            formatter={(value, _name, item) => {
              const applications =
                (item as { payload?: { applications?: number } })?.payload?.applications ?? 0;
              return [`${value}% of ${applications}`, "Replied"];
            }}
          />
          <Bar dataKey="rate" radius={[4, 4, 0, 0]} name="Response rate">
            {shaped.map((entry) => (
              <Cell key={entry.group} fill={BRAND} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
