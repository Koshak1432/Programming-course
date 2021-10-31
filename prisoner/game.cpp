#include "game.h"

Matrix::Matrix(std::size_t rows, std::size_t cols) : matrix_(std::vector<std::vector<int>>(rows, std::vector<int>(cols, 0)))
{
}

int Matrix::choices_to_idx(const std::vector<Choice> &choices) const //choices to binary code
{
	return static_cast<int>(choices[0]) * 4 + static_cast<int>(choices[1]) * 2 + static_cast<int>(choices[2]) * 1;
}

std::vector<int> Matrix::get_payoffs(const std::vector<Choice> &choices) const
{
	return matrix_[choices_to_idx(choices)];
}

std::vector<int> &Matrix::operator [](std::size_t idx)
{
	assert (idx < ROWS && idx >= 0);
	return matrix_[idx];
}

Matrix::Matrix()
{
	matrix_ =
	{
		std::vector<int>{7, 7, 7},
		std::vector<int>{3, 3, 9},
		std::vector<int>{3, 9, 3},
		std::vector<int>{9, 3, 3},
		std::vector<int>{0, 5, 5},
		std::vector<int>{5, 0, 5},
		std::vector<int>{5, 5, 0},
		std::vector<int>{1, 1, 1},
	};
}

void Game::step(const std::vector<std::unique_ptr<Strategy>> &strategies)
{
	//ask for choices
	for (std::size_t i = 0; i < strategies.size(); ++i)
	{
		res_.choices_[i] = strategies[i]->get_choice();
	}
	//get payoffs
	res_.payoffs_ = matrix_.get_payoffs(res_.choices_);
	//add to scores
	for (std::size_t i = 0; i < strategies.size(); ++i)
	{
		res_.scores_[i] += res_.payoffs_[i]; //if strategies more than 3???? todo
	}

	//add to history in each strategy todo
}

Result::Result(int cols) :choices_(cols), payoffs_(cols), scores_(cols)
{
}
