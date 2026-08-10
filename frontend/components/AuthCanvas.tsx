"use client";

import { useEffect, useRef } from "react";

interface Vec2 {
  x: number;
  y: number;
}

interface CurveData {
  p0: Vec2;
  p1: Vec2;
  p2: Vec2;
  p3: Vec2;
  drawProgress: number;
  drawSpeed: number;
  opacity: number;
  glowSize: number;
}

interface CurveDot {
  curveIdx: number;
  t: number;
  speed: number;
  size: number;
}

function bezierPoint(
  t: number,
  p0: Vec2,
  p1: Vec2,
  p2: Vec2,
  p3: Vec2
): Vec2 {
  const mt = 1 - t;
  return {
    x: mt * mt * mt * p0.x + 3 * mt * mt * t * p1.x + 3 * mt * t * t * p2.x + t * t * t * p3.x,
    y: mt * mt * mt * p0.y + 3 * mt * mt * t * p1.y + 3 * mt * t * t * p2.y + t * t * t * p3.y
  };
}

function drawPartialBezier(
  ctx: CanvasRenderingContext2D,
  curve: CurveData,
  progress: number,
  steps = 90
) {
  const count = Math.max(2, Math.round(progress * steps));
  ctx.beginPath();
  for (let i = 0; i <= count; i++) {
    const t = i / steps;
    const point = bezierPoint(t, curve.p0, curve.p1, curve.p2, curve.p3);
    if (i === 0) {
      ctx.moveTo(point.x, point.y);
    } else {
      ctx.lineTo(point.x, point.y);
    }
  }
}

function buildCurves(width: number, height: number) {
  const vp = { x: 0, y: height * 0.5 };
  const xFractions = [0.3, 0.45, 0.58, 0.7, 0.85, 1.02];
  const curves: CurveData[] = [];

  xFractions.forEach((xf, i) => {
    const count = xFractions.length;
    const midness = 1 - Math.abs(i - (count - 1) / 2) / ((count - 1) / 2);
    const opacity = 0.16 + midness * 0.44;
    const glowSize = 5 + midness * 14;
    const cp1x = xf * width * 0.72;
    const cp1y = height * (0.1 + i * 0.02);
    const cp2x = width * 0.06;
    const cp2y = vp.y - height * (0.12 - i * 0.007);

    curves.push({
      p0: { x: xf * width, y: -height * 0.04 },
      p1: { x: cp1x, y: cp1y },
      p2: { x: cp2x, y: cp2y },
      p3: { ...vp },
      opacity,
      glowSize,
      drawProgress: 0,
      drawSpeed: 0.0035 + Math.random() * 0.004
    });
    curves.push({
      p0: { x: xf * width, y: height * 1.04 },
      p1: { x: cp1x, y: height - cp1y },
      p2: { x: cp2x, y: vp.y + height * (0.12 - i * 0.007) },
      p3: { ...vp },
      opacity,
      glowSize,
      drawProgress: 0,
      drawSpeed: 0.0035 + Math.random() * 0.004
    });
  });

  const dots: CurveDot[] = curves.map((_, index) => ({
    curveIdx: index,
    t: 0.15 + (index / curves.length) * 0.7,
    speed: 0.0007 + Math.random() * 0.0013,
    size: 2.8 + Math.random() * 2.2
  }));

  return { curves, dots, vp };
}

export default function AuthCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rafRef = useRef<number>(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) {
      return;
    }
    const context = canvas.getContext("2d");
    if (!context) {
      return;
    }
    const ctx = context;

    let width = window.innerWidth;
    let height = window.innerHeight;
    let state = buildCurves(width, height);

    function resize() {
      const canvasElement = canvasRef.current;
      if (!canvasElement) {
        return;
      }
      width = window.innerWidth;
      height = window.innerHeight;
      canvasElement.width = width;
      canvasElement.height = height;
      state = buildCurves(width, height);
    }

    resize();
    window.addEventListener("resize", resize);
    const start = performance.now();

    function frame(now: number) {
      ctx.clearRect(0, 0, width, height);
      const bg = ctx.createRadialGradient(
        state.vp.x,
        state.vp.y,
        0,
        width * 0.5,
        height * 0.5,
        Math.max(width, height) * 1.1
      );
      bg.addColorStop(0, "#0e1830");
      bg.addColorStop(0.38, "#08101f");
      bg.addColorStop(1, "#040810");
      ctx.fillStyle = bg;
      ctx.fillRect(0, 0, width, height);

      const elapsed = (now - start) / 1000;
      state.curves.forEach((curve) => {
        curve.drawProgress = Math.min(1, curve.drawProgress + curve.drawSpeed);
        if (curve.drawProgress < 0.02) {
          return;
        }

        ctx.save();
        ctx.lineWidth = 3.5;
        ctx.strokeStyle = `rgba(140,185,255,${curve.opacity * 0.22})`;
        ctx.shadowBlur = curve.glowSize * 1.8;
        ctx.shadowColor = "rgba(80,140,255,0.55)";
        ctx.lineCap = "round";
        drawPartialBezier(ctx, curve, curve.drawProgress);
        ctx.stroke();
        ctx.restore();

        ctx.save();
        ctx.lineWidth = 0.85;
        ctx.strokeStyle = `rgba(200,225,255,${curve.opacity * curve.drawProgress})`;
        ctx.shadowBlur = 0;
        ctx.lineCap = "round";
        drawPartialBezier(ctx, curve, curve.drawProgress);
        ctx.stroke();
        ctx.restore();
      });

      const pulse = 0.5 + 0.5 * Math.sin(elapsed * 1.6);
      const glow = ctx.createRadialGradient(
        state.vp.x,
        state.vp.y,
        0,
        state.vp.x,
        state.vp.y,
        70 + pulse * 28
      );
      glow.addColorStop(0, "rgba(100,165,255,0.22)");
      glow.addColorStop(0.4, "rgba(55,110,220,0.08)");
      glow.addColorStop(1, "transparent");
      ctx.beginPath();
      ctx.arc(state.vp.x, state.vp.y, 70 + pulse * 28, 0, Math.PI * 2);
      ctx.fillStyle = glow;
      ctx.fill();

      state.dots.forEach((dot) => {
        const curve = state.curves[dot.curveIdx];
        if (curve.drawProgress < 0.1) {
          return;
        }
        dot.t -= dot.speed;
        if (dot.t < -0.06) {
          dot.t = 1.04;
        }
        const currentT = Math.max(0, Math.min(1, dot.t));
        if (currentT > curve.drawProgress) {
          return;
        }
        const fade =
          currentT > 0.88
            ? (1 - currentT) / 0.12
            : currentT < 0.1
              ? currentT / 0.1
              : 1;
        if (fade <= 0) {
          return;
        }
        const pos = bezierPoint(currentT, curve.p0, curve.p1, curve.p2, curve.p3);
        const halo = ctx.createRadialGradient(
          pos.x,
          pos.y,
          0,
          pos.x,
          pos.y,
          dot.size * 5
        );
        halo.addColorStop(0, `rgba(160,210,255,${0.32 * fade})`);
        halo.addColorStop(1, "transparent");
        ctx.beginPath();
        ctx.arc(pos.x, pos.y, dot.size * 5, 0, Math.PI * 2);
        ctx.fillStyle = halo;
        ctx.fill();

        ctx.beginPath();
        ctx.arc(pos.x, pos.y, dot.size, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(215,235,255,${0.92 * fade})`;
        ctx.fill();
      });

      rafRef.current = requestAnimationFrame(frame);
    }

    rafRef.current = requestAnimationFrame(frame);
    return () => {
      window.removeEventListener("resize", resize);
      cancelAnimationFrame(rafRef.current);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      className="absolute inset-0 h-full w-full"
      aria-hidden="true"
    />
  );
}
