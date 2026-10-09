import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

/** CSV, TSV 를 읽은 줄을 표로 그린다. 첫 줄이 머리다. 칸은 글자로만 그린다. */
export function CsvTable({ rows }: { rows: string[][] }) {
  const [head, ...body] = rows;
  if (head === undefined) {
    return <p className="text-sm text-muted-foreground">빈 파일이에요.</p>;
  }
  return (
    <Table data-testid="workspace-csv-table">
      <TableHeader>
        <TableRow>
          {head.map((cell, index) => (
            <TableHead key={index}>{cell}</TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {body.map((row, rowIndex) => (
          <TableRow key={rowIndex}>
            {row.map((cell, index) => (
              <TableCell key={index} className="whitespace-pre-wrap">
                {cell}
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
