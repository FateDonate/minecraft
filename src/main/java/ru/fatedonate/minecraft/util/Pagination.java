package ru.fatedonate.minecraft.util;

import java.util.List;

public final class Pagination {
    private Pagination() {
    }

    public static <T> Page<T> paginate(List<T> values, int page, int pageSize) {
        if (values.isEmpty()) {
            return new Page<>(List.of(), 0, 1);
        }

        final int totalPages = Math.max(1, (int) Math.ceil(values.size() / (double) pageSize));
        final int safePage = Math.max(0, Math.min(page, totalPages - 1));

        final int fromIndex = safePage * pageSize;
        final int toIndex = Math.min(values.size(), fromIndex + pageSize);

        return new Page<>(values.subList(fromIndex, toIndex), safePage, totalPages);
    }

    public record Page<T>(List<T> values, int page, int totalPages) {
    }
}
