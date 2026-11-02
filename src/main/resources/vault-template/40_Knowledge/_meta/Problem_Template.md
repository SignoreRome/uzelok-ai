---
type: problem
domain: Algorithms
topic: Two_Pointers
problem_source: leetcode
problem_ref: "15"
problem_pattern: sort_and_skip_duplicates
problem_difficulty: medium
status: active
created: YYYY-MM-DD
updated: YYYY-MM-DD
summary: "3Sum: сортировка плюс два указателя с пропуском дублей — O(n²) без хеш-таблицы"
---

# LC 15 · 3Sum

## Условие

Массив `nums`; вернуть все уникальные тройки `(a, b, c)`, где `a + b + c = 0` и индексы различны.

## Идея

Сортируем массив. Фиксируем `i`, дальше классический two pointers на `[i+1, n-1]`: если сумма меньше нуля — двигаем `left`, больше — `right`. Дубли пропускаем на всех трёх уровнях, иначе тройки повторятся.

Сложность: `O(n²)` по времени, `O(1)` дополнительной памяти (без учёта сортировки и результата).

## Решение

```java
for (int i = 0; i < n - 2; i++) {
    if (i > 0 && nums[i] == nums[i - 1]) continue;
    int l = i + 1, r = n - 1;
    while (l < r) {
        int s = nums[i] + nums[l] + nums[r];
        if (s < 0) l++;
        else if (s > 0) r--;
        else {
            res.add(List.of(nums[i], nums[l], nums[r]));
            l++; r--;
            while (l < r && nums[l] == nums[l - 1]) l++;
        }
    }
}
```

## Что запомнить

- Пропуск дублей нужен **после** фиксации пары, а не до: иначе теряются валидные тройки.
- `i` идёт до `n-3`, а не до `n-1` — экономит проверки.
- Тот же скелет с `target` вместо нуля решает 3Sum Closest и 4Sum с внешним циклом.

## Ошибки

- Первая попытка использовала `HashSet` внутри цикла — `O(n²)` памяти и дубли в результате.
- Забывал `l++` после успешной пары — бесконечный цикл на одинаковых значениях.

## Связанные заметки

[[Two_Pointers]]
