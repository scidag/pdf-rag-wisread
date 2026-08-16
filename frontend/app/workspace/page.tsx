import { redirect } from "next/navigation";

// 旧文档级工作台已废弃：项目级改造后统一入口为 /projects（路由保留兼容）
export default function WorkspacePage() {
  redirect("/projects");
}
