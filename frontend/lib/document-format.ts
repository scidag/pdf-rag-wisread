export function isSupportedDocumentFilename(filename: string) {
  return /\.(pdf|docx|xlsx|txt|md)$/i.test(filename);
}

export function documentUnit(fileType: string | null) {
  switch (fileType?.toLowerCase()) {
    case "docx":
      return "段";
    case "xlsx":
      return "工作表";
    case "txt":
    case "md":
      return "行";
    default:
      return "页";
  }
}

export function formatDocumentCount(fileType: string | null, count: number | null) {
  if (count === null || count === undefined) {
    return "";
  }
  return `${count} ${documentUnit(fileType)}`;
}

export function formatSourceLocation(source: {
  pageStart: number;
  pageEnd: number;
  locationLabel?: string | null;
}) {
  const label = source.locationLabel ?? "页";
  const range = source.pageStart === source.pageEnd
    ? String(source.pageStart)
    : `${source.pageStart}-${source.pageEnd}`;
  if (label.startsWith("工作表")) {
    return `${label} 第 ${range} 行`;
  }
  return `第 ${range} ${label}`;
}
