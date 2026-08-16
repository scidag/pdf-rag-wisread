"use client";

import { ArrowRight, FileText, MessageSquare } from "lucide-react";
import type { Project } from "@/lib/types";

interface ProjectCardProps {
  project: Project;
  onClick: () => void;
}

export default function ProjectCard({ project, onClick }: ProjectCardProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group relative flex flex-col gap-[14px] overflow-hidden rounded-[14px] border border-border bg-[rgba(6,12,26,0.74)] p-[18px] text-left transition hover:-translate-y-0.5 hover:border-[rgba(80,140,255,0.38)] hover:bg-[rgba(80,140,255,0.06)]"
    >
      <div className="pointer-events-none absolute -right-6 -top-6 h-[90px] w-[90px] rounded-full bg-[radial-gradient(circle,rgba(80,140,255,0.16),transparent_68%)]" />
      <div className="flex items-center gap-3">
        <span className="flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-[11px] border border-[rgba(80,140,255,0.3)] bg-gradient-to-br from-[#17365f] to-[#0d1f3d] text-[rgba(150,200,255,0.9)]">
          <FileText className="h-5 w-5" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate font-serif text-[0.92rem] font-bold text-[rgba(220,235,255,0.92)]">
            {project.name}
          </div>
          <div className="mt-1 flex items-center gap-2 font-mono text-[0.64rem] text-[rgba(150,185,235,0.42)]">
            <span>{project.documentCount} 个文档</span>
            <span>·</span>
            <span>{project.conversationCount} 个会话</span>
          </div>
        </div>
      </div>
      {project.description && (
        <p className="line-clamp-2 text-[0.78rem] leading-[1.65] text-[rgba(165,195,235,0.55)]">
          {project.description}
        </p>
      )}
      <div className="flex items-center justify-between gap-[10px] border-t border-[rgba(255,255,255,0.05)] pt-3">
        <div className="flex items-center gap-[14px] text-[0.7rem] font-semibold text-[rgba(170,200,240,0.55)]">
          <span className="inline-flex items-center gap-[5px]">
            <FileText className="h-3.5 w-3.5 text-[rgba(110,170,255,0.75)]" />
            {project.documentCount}
          </span>
          <span className="inline-flex items-center gap-[5px]">
            <MessageSquare className="h-3.5 w-3.5 text-[rgba(110,170,255,0.75)]" />
            {project.conversationCount}
          </span>
        </div>
        <span className="inline-flex items-center gap-1 rounded-[7px] px-[9px] py-[5px] text-[0.72rem] font-bold text-[rgba(110,165,255,0.85)] transition group-hover:bg-[rgba(80,140,255,0.14)]">
          进入 <ArrowRight className="h-3.5 w-3.5" />
        </span>
      </div>
    </button>
  );
}
